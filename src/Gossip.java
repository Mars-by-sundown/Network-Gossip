/*TODO:
    -Change Base ServerPort
    -Grab nodeNumber from arg pass
    -handle different console commands
    -implement queuing for incoming commands, must finish trades and calcs before beginning the next one
*/

import java.io.*;
import java.net.*;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

enum messageTypes {CONSOLE, REQUEST, REPLY, ACK, NONE};
enum commands{
    SHOWCOMMANDS,
    SHOWLOCALS,
    PING,
    MINMAX,
    AVG,
    SIZE,
    NEWVAL,
    DELNODE,
    KILLNET,
    LIFETIME,
    NEWLIMIT,
    NONE
}

class GossipData implements Serializable {
    int sentValue;
    int average; //current average of network
    int highestVal; //highest value seen in the network
    int highestVal_ID;
    int lowestVal;  //lowest value seen in the network
    int lowestVal_ID;

    int nodeID; //unique id of the sending node

    int originatorID;   //populated by the originator before starting a gossip session

    String transactionID; //a unique ID created by the originator before starting a gossip session
        //perhaps the nodeID and the current lifetime cycle count at message origination
    
    messageTypes msgType = messageTypes.NONE; //tracks RRA or console
    commands command = commands.NONE;  //holds what the command is

    String messageString;

    public String toString(){
        return "Sender ID: " + nodeID + ", Msg: " + messageString;
    }
}

class NodeInfo{
    int nodeID;
    InetAddress nodeIP;
    int serverPort;
    int dataValue; //random data value 
    int minNetworkVal;
    int minNetworkVal_ID;
    int maxNetworkVal;
    int maxNetworkVal_ID;
    int currentAverage; //average of the network
    int currentSize = 0; //calculate size of network with inverse of average
    int numOfCycles_total = 0; //lifetime count of cycles
    int currentCycles = 0;
    int cycleLimit = 20; //set to 20 be default
    boolean hasNodeAbove = false;
    boolean hasNodeBelow = false;
    
    //conversation tracking
    boolean hasCurrentConv = false;
    String currentTransactionID = "";
    int conversationPartnerID = -1;
    BlockingQueue<GossipData> consoleQueue; //holds requests from the console
    BlockingQueue<GossipData> requestQueue; //holds requests from other nodes
    BlockingQueue<GossipData> conversationQueue; //holds replies to current conversation
    //decision to use a queue for the current conversation is to allow ability to
    // potentially receive multiple replies, as we may in the future wish to be able to have
    // more than one conversation at a time (hence transID)


    NodeInfo(int id, int serverPort){
        this.nodeID = id;
        this.serverPort = serverPort;
        generateNewValue(); //populate our dataValue with a random value on construction

        //at this moment in time we are unaware of any node except ourself so these are all "true"
        minNetworkVal = dataValue;
        maxNetworkVal = dataValue;
        currentAverage = dataValue;

        consoleQueue = new ArrayBlockingQueue<GossipData>(5);
        requestQueue = new ArrayBlockingQueue<GossipData>(10);
        conversationQueue = new ArrayBlockingQueue<GossipData>(5);
    }

    public void generateNewValue(){
        //generates a new value between 0-99
        Random rng = new Random();
        dataValue = rng.nextInt(100);
    }

    public String toString(){
        return 
        "####################################################################################################\n" +
        "   NodeID: " + nodeID + " port: " + serverPort + "\n" +
        "   node Values (local value, avg, min, max): (" + dataValue + ", " + currentAverage + ", " + minNetworkVal + ", " + maxNetworkVal + ")\n" +
        "   current size: " + currentSize +
        "   cycle Info (limit, lifetime): (" + cycleLimit + ", " + numOfCycles_total + ")\n" +
        "####################################################################################################\n";

    }
}




class GossipDirector extends Thread{
    NodeInfo locals;
    boolean keepAlive = true;
    GossipData incomingData; //will be pulled from a queue

    GossipDirector(NodeInfo n){
        locals = n;
    }

    public void run(){
        while(keepAlive){
            incomingData = null;
            if(!(locals.hasCurrentConv)){
                //no active conversation, free to get a new one
                if(!locals.consoleQueue.isEmpty()){
                    //if there is a console request service it
                    incomingData = locals.consoleQueue.poll();
                    incomingData.msgType = messageTypes.REQUEST;
                    switch(incomingData.command){
                        case SHOWCOMMANDS: displayAllCommands(); break;
                        case SHOWLOCALS: displayLocals(incomingData); break;
                        case PING: ping(incomingData, incomingData.msgType, 1); ping(incomingData, incomingData.msgType, -1); break;
                    }

                }else if(!locals.requestQueue.isEmpty()){
                    //if there is a node request service it
                    incomingData = locals.requestQueue.poll();
                    switch(incomingData.command){
                        case PING: ping(incomingData, incomingData.msgType, 0); break;
                    }
                }else{
                    //nothing left to do, skip to next iteration of loop somehow
                    
                }
            }else{
                if(!locals.conversationQueue.isEmpty()){
                    //handle the current transaction
                    incomingData = locals.conversationQueue.poll();
                    switch(incomingData.command){
                        case PING: ping(incomingData, incomingData.msgType,0); break;
                    }
                }

            }
        }
    }

    //t
    private void displayAllCommands(){
        System.out.println("Showing all commands.");
        System.out.println("    t - show all commands");
        System.out.println("    l - display local values on all nodes (l as in Lima)");
        System.out.println("    p - ping neighbors of this node");
        System.out.println("    m - calculate network min/max, display on all nodes");
        System.out.println("    a - calculate network average, show on all nodes");
        System.out.println("    z - calculate network size, show on all nodes");
        System.out.println("    v - force new random values on network, all nodes");
        System.out.println("    d - delete the current node");
        System.out.println("    k - kill the network");
        System.out.println("    N - N is an integer value, sets the cycle limit of the network (default 20)");
    }

    // l
    private void displayLocals(GossipData gossipObj){
        System.out.println(locals);
        gossipObj.msgType = messageTypes.REQUEST;
        sendMsg(gossipObj, locals.serverPort + 1);
        sendMsg(gossipObj, locals.serverPort - 1);
    }

    // p
    private void ping(GossipData gossipObj, messageTypes pingType, int offset){
        switch(pingType){
            case REQUEST: 
                //we are sending a ping
                if(gossipObj.originatorID == locals.nodeID){
                    //we are orginating the request and starting the transaction
                    System.out.println("SENDING PING REQUEST");
                    locals.hasCurrentConv = true;

                }else{
                    //we are receiving this request from another node and just need to reply back
                    gossipObj.msgType = messageTypes.REPLY;
                    offset = gossipObj.originatorID - locals.nodeID;
                }
                sendMsg(gossipObj, (locals.serverPort + offset));
                System.out.println(locals.nodeID + " sending " + gossipObj.msgType + " to " + (locals.serverPort + offset));

                break;
            case REPLY:
                //we are receiving a response to our ping, mark that node as alive
                locals.hasCurrentConv = false; //conversation over
                if(gossipObj.nodeID > locals.nodeID){
                    locals.hasNodeAbove = true;
                }else{
                    locals.hasNodeBelow = true;
                }
                System.out.println("ping results - Node Above: " + locals.hasNodeAbove + ", Node Below: " + locals.hasNodeBelow + "\n");
                break;
        }
    }




    private GossipData setCycleLimit(GossipData gd, int x){
        locals.cycleLimit = x; //set this nodes value to the new limit

        //prepare to send the message onwards
        gd.command = commands.NEWLIMIT; //set type of command
        gd.msgType = messageTypes.REQUEST; //this is a new request sent from the console
        gd.sentValue = x;
        return gd;
    }

    private String newTransID(){
        StringBuilder newStr = new StringBuilder();
        newStr.append(locals.numOfCycles_total);
        newStr.append(locals.nodeID);
        return newStr.toString();
    }

    private void sendMsg(GossipData outgoingGossip, int targetPort){
        //sends a gossipData message to the recipient node
        try{
            DatagramSocket DGSocket = new DatagramSocket();
            InetAddress IPAddress = InetAddress.getByName("localhost");

            //open a byte array output stream
            ByteArrayOutputStream byteoutStream = new ByteArrayOutputStream();

            //use the byte out stream to send the serialized gossipObj
            ObjectOutputStream outStream = new ObjectOutputStream(byteoutStream);

            outgoingGossip.nodeID = locals.nodeID;
            outStream.writeObject(outgoingGossip);

            //the serialized data object is converted and stored in a byte array
            //it is then placed into a datagram packet, and sent to the IPAddress and port
            byte[] data = byteoutStream.toByteArray();
            DatagramPacket sendPacket = new DatagramPacket(data, data.length, IPAddress, targetPort);
            DGSocket.send(sendPacket);
            DGSocket.close();
        }catch(UnknownHostException UNH){
            UNH.printStackTrace();
        }catch(IOException IOE){
            IOE.printStackTrace();
        }

    }
}

public class Gossip {
    public static int serverPort = 48100;
    public static int NodeNumber = 0; //THIS COMES FROM FIRST ARGUMENT PASSED
    public static NodeInfo nodeLocalInfo;

    public static void main(String[] args) throws Exception{
        if(args.length > 0){
            try{
                NodeNumber = Integer.parseInt(args[0]);

            }catch (NumberFormatException NFE){
                System.out.println("The only argument Gossip accepts is an integer number");
            }
        }
        serverPort += NodeNumber;
        nodeLocalInfo = new NodeInfo(NodeNumber, serverPort);
        System.out.println("Nicholas Ragano's Gossip Server 1.0 starting up, listening at port " + Gossip.serverPort + ".");

        //Start a thread for our Gossip Director
        GossipDirector GDir = new GossipDirector(nodeLocalInfo);
        Thread GdirThread = new Thread(GDir);
        GdirThread.start(); 

        //Start a thread for the ConsoleMonitor to listen for console commands
        ConsoleMonitor CM = new ConsoleMonitor();
        Thread CMThread = new Thread(CM);
        CMThread.start();

        try{
            //create our datagram listener socket
            DatagramSocket DGListenerSocket = new DatagramSocket(Gossip.serverPort);
            //create a byte buffer to hold incoming packets
            byte[] incomingData = new byte[1024]; //can accept a message of 1024 bytes
            // nodeLocalInfo.nodeIP =  InetAddress.getByName("localhost");

            boolean keepAlive = true; //keep the datagram listener running
            //loop to listen for incoming packets from consolemonitor or from other gossip servers
            while(keepAlive){
                //listen a receive packets
                DatagramPacket incomingPacket = new DatagramPacket(incomingData, incomingData.length);
                DGListenerSocket.receive(incomingPacket);

                //take received object and begin to decode it into a GossipData Object
                byte[] data = incomingPacket.getData();
                ByteArrayInputStream inStream = new ByteArrayInputStream(data);
                ObjectInputStream objInStream = new ObjectInputStream(inStream);
                try{
                    GossipData gossipObj = (GossipData) objInStream.readObject();
                    switch(gossipObj.msgType){
                        case CONSOLE: gossipObj.originatorID = nodeLocalInfo.nodeID; nodeLocalInfo.consoleQueue.add(gossipObj); break;
                        case REQUEST: nodeLocalInfo.requestQueue.add(gossipObj); break;
                        case REPLY: nodeLocalInfo.conversationQueue.add(gossipObj); break;
                        case ACK: 
                            //finalize values, reset flags, service next request, manager should handle this
                            nodeLocalInfo.conversationQueue.add(gossipObj);
                            break;
                        case NONE: break;
                        default: break;
                    }
                }catch(ClassNotFoundException CNF) {
                    CNF.printStackTrace();
                }
            }

            //server clean up before end
            DGListenerSocket.close(); //close the socket before we shutdown

        } catch (SocketException SE){
            SE.printStackTrace();
        } catch (IOException IOE){
            IOE.printStackTrace();
        }
    }
}

class ConsoleMonitor implements Runnable{
    public void run(){
        BufferedReader consoleIn = new BufferedReader(new InputStreamReader(System.in));
        boolean keepAlive = true;
        try{
            String inString;
            do{
                System.out.println("CM: Enter a string to send to the gossipServer, or type quit/stopserver: ");
                System.out.flush();
                inString = consoleIn.readLine();

                GossipData gossipObj = new GossipData();
                gossipObj.messageString = inString;
                gossipObj.msgType = messageTypes.CONSOLE;

                if(inString.indexOf("quit") > -1){
                    //user requested to quit
                    System.out.println("CM: Exiting by user request.\n");
                    //call to quit process
                    keepAlive = false;
                }else{
                    //else its something
                    switch(inString){
                        case "t": gossipObj.command = commands.SHOWCOMMANDS; break;
                        case "l": gossipObj.command = commands.SHOWLOCALS; break;
                        case "p": gossipObj.command = commands.PING; break;
                        case "m": gossipObj.command = commands.MINMAX; break;
                        case "a": gossipObj.command = commands.AVG; break;
                        case "z": gossipObj.command = commands.SIZE; break;
                        case "v": gossipObj.command = commands.NEWVAL; break;
                        case "d": gossipObj.command = commands.DELNODE; break;
                        case "k": gossipObj.command = commands.KILLNET; break;
                        case "y": gossipObj.command = commands.LIFETIME; break;
                        default: 
                            gossipObj.command = commands.NONE;
                            System.out.println("Unrecognized argument passed to GossipWorker");
                    }
                }   

                try{
                    System.out.println("CM: Preparing the datagram packet now...");
                    DatagramSocket DGSocket = new DatagramSocket();
                    InetAddress IPAddress = InetAddress.getByName("localhost");

                    //open a byte array output stream
                    ByteArrayOutputStream byteoutStream = new ByteArrayOutputStream();

                    //use the byte out stream to send the serialized gossipObj
                    ObjectOutputStream outStream = new ObjectOutputStream(byteoutStream);
                    outStream.writeObject(gossipObj);

                    //the serialized data object is converted and stored in a byte array
                    //it is then placed into a datagram packet, and sent to the IPAddress and port
                    byte[] data = byteoutStream.toByteArray();
                    DatagramPacket sendPacket = new DatagramPacket(data, data.length, IPAddress, Gossip.serverPort);
                    DGSocket.send(sendPacket);
                    DGSocket.close();

                }catch (UnknownHostException UHE){
                    System.out.println("\nCM: Unknown Host Exception.\n");
                    UHE.printStackTrace();
                }
            }while(true);
        }catch (IOException IOE){
            IOE.printStackTrace();
        }
    }
}
