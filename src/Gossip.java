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
import java.util.HashMap;



enum messageTypes {
    CONSOLE, 
    REQUEST, 
    REPLY, 
    ACK, 
    NONE
}
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
    int cycleNumber = 0;
    int targetPort; 

    int nodeID; //unique id of the sending node
    int nodePort;

    int originatorID;   //populated by the originator before starting a gossip session
    String transID;
    messageTypes msgType = messageTypes.NONE; //tracks RRA or console
    commands command = commands.NONE;  //holds what the command is

    String messageString;

    public GossipData(){

    }

    public GossipData(GossipData copyFrom){
        this.sentValue = copyFrom.sentValue;
        this.average = copyFrom.average;
        this.highestVal = copyFrom.highestVal;
        this.highestVal_ID = copyFrom.highestVal_ID;
        this.lowestVal = copyFrom.lowestVal;
        this.lowestVal_ID = copyFrom.lowestVal_ID;
        this.targetPort = copyFrom.targetPort;
        this.nodeID = copyFrom.nodeID;
        this.nodePort = copyFrom.nodePort;
        this.originatorID = copyFrom.originatorID;
        this.msgType = copyFrom.msgType;
        this.command = copyFrom.command;
        this.cycleNumber = copyFrom.cycleNumber;
        this.transID = copyFrom.transID;
        

    }

    // public String toString(){
    //     return "Sender ID: " + this.nodeID + ", Msg: " + messageString;
    // }
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
    int lifetimeCycles = 0; //lifetime count of cycles
    int cyclesAtFirstRequest = 0;
    int currentCycles = 0;
    int cycleLimit = 20; //set to 20 be default
    boolean hasNodeAbove = false;
    boolean hasNodeBelow = false;
    
    //conversation tracking
    boolean nodeIsFree = true;
    int replyFromPort;
    long timeLastSent;
    long timeoutTime = 1500;
    boolean doubleToggle = false;

    BlockingQueue<GossipData> consoleQueue; //holds requests from the console
    BlockingQueue<GossipData> requestQueue; //holds requests from other nodes
    BlockingQueue<GossipData> conversationQueue; //holds replies to current conversation
    BlockingQueue<String> transactionQueue;
    HashMap<String, GossipData> activeTransactionStatusMap;
    //decision to use a queue for the current conversation is to allow ability to
    // potentially receive multiple replies, as we may in the future wish to be able to have
    // more than one conversation at a time (hence transID)

    

    NodeInfo(int id, int sp){
        nodeID = id;
        serverPort = sp;
        generateNewValue(false); //populate our dataValue with a random value on construction

        //at this moment in time we are unaware of any node except ourself so these are all "true"
        minNetworkVal = dataValue;
        maxNetworkVal = dataValue;
        currentAverage = dataValue;

        consoleQueue = new ArrayBlockingQueue<GossipData>(15);
        requestQueue = new ArrayBlockingQueue<GossipData>(15);
        conversationQueue = new ArrayBlockingQueue<GossipData>(15);
        transactionQueue = new ArrayBlockingQueue<String>(10);
        activeTransactionStatusMap = new HashMap<String, GossipData>();
    }

    public String createTransID(){
        StringBuilder retStr = new StringBuilder();
        retStr.append(lifetimeCycles);
        retStr.append(".");
        retStr.append(serverPort);
        return retStr.toString();
    }


    public void startTransaction(){
        nodeIsFree = false;
        timeLastSent = System.currentTimeMillis();
    }

    public void updateTransaction(){
        timeLastSent = System.currentTimeMillis();
    }

    public boolean waitTimeExceeded(){
        //returns true if we have waited longer than the timeoutTime since receiving a reply
        return ((System.currentTimeMillis() - timeLastSent) > timeoutTime);
    }

    public void resetTransaction(){
        nodeIsFree = true;
        doubleToggle = false;
        //clear this item from the head of the queue, and erase its history
        activeTransactionStatusMap.remove(transactionQueue.remove());

    }

    public void updateCycles(){
        lifetimeCycles++;
        currentCycles++;
    }

    public void generateNewValue(boolean verbose){
        //generates a new value between 0-99
        if(verbose){
            System.out.println("  Generating new value");
            System.out.println("  -> old value : " + dataValue);
        }

        Random rng = new Random();
        dataValue = rng.nextInt(100);
        if(verbose){System.out.println("  -> new value : " + dataValue);}
        
    }

    public String toString(){
        return 
        "####################################################################################################\n" +
        "   NodeID: " + nodeID + " port: " + serverPort + "\n" +
        "   node Values (local value, avg, min, max): (" + dataValue + ", " + currentAverage + ", " + minNetworkVal + ", " + maxNetworkVal + ")\n" +
        "   current size: " + currentSize +
        "   cycle Info (limit, lifetime): (" + cycleLimit + ", " + lifetimeCycles + ")\n" +
        "####################################################################################################\n";

    }
}




class GossipDirector extends Thread{
    NodeInfo locals;
    boolean keepAlive = true;
    GossipData incomingData; //will be pulled from a queue

    GossipDirector(NodeInfo n){
        this.locals = n;
    }

    public void run(){
        while(keepAlive){
            if(locals.nodeIsFree){
                //we can first check for console commands
                // and then for incoming requests
                if(!locals.consoleQueue.isEmpty()){
                    //there is a console request
                    incomingData = locals.consoleQueue.remove();
                    switch(incomingData.command){
                        case AVG: networkAverage(incomingData); break;
                        case DELNODE: break;
                        case KILLNET: break;
                        case LIFETIME: break;
                        case MINMAX: break;
                        case NEWLIMIT: break;
                        case NEWVAL: regenerateNetwork(incomingData); break;
                        case NONE: break;
                        case PING: ping(incomingData); break;
                        case SHOWCOMMANDS: break;
                        case SHOWLOCALS: displayLocals(incomingData); break;
                        case SIZE: break;
                        default: break;
                    }

                }else if(!locals.requestQueue.isEmpty()){
                    //console queue is empty, service incoming requests
                    incomingData = locals.requestQueue.remove();
                    switch(incomingData.command){
                        case AVG: networkAverage(incomingData); break;
                        case DELNODE: break;
                        case KILLNET: break;
                        case LIFETIME: break;
                        case MINMAX: break;
                        case NEWLIMIT: break;
                        case NEWVAL: regenerateNetwork(incomingData); break;
                        case NONE: break;
                        case PING: ping(incomingData); break;
                        case SHOWCOMMANDS: break;
                        case SHOWLOCALS: displayLocals(incomingData); break;
                        case SIZE: break;
                        default: break;
                    }
                }

            }else{
                //we are in a conversation
                if((!locals.transactionQueue.isEmpty()) && (locals.activeTransactionStatusMap.get(locals.transactionQueue.peek()).msgType != messageTypes.REQUEST)){
                    incomingData = locals.activeTransactionStatusMap.get(locals.transactionQueue.peek());
                    switch(incomingData.command){
                        case AVG: networkAverage(incomingData); break;
                        case DELNODE: break;
                        case KILLNET: break;
                        case LIFETIME: break;
                        case MINMAX: break;
                        case NEWLIMIT: break;
                        case NEWVAL: break;
                        case NONE: break;
                        case PING: ping(incomingData); break;
                        case SHOWCOMMANDS: break;
                        case SHOWLOCALS: break;
                        case SIZE: break;
                        default: break;
                    }
                }

                // if(!locals.conversationQueue.isEmpty()){
                //     incomingData = locals.conversationQueue.remove();
                //     switch(incomingData.command){
                //         case AVG: networkAverage(incomingData); break;
                //         case DELNODE: break;
                //         case KILLNET: break;
                //         case LIFETIME: break;
                //         case MINMAX: break;
                //         case NEWLIMIT: break;
                //         case NEWVAL: break;
                //         case NONE: break;
                //         case PING: ping(incomingData); break;
                //         case SHOWCOMMANDS: break;
                //         case SHOWLOCALS: break;
                //         case SIZE: break;
                //         default: break;
                //     }
                else if(locals.waitTimeExceeded()){
                    //the wait time has been exceeded. 
                    //  dump the transaction and go service console and request queues
                    System.out.println("!!!!!WAIT TIME EXCEEDED, DROPPING TRANSACTION!!!!!!!");
                    System.out.println("    Failed to receive reply from port: " + locals.replyFromPort);
                    locals.resetTransaction();
                    
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
    private void displayLocals(GossipData inMessage){
        GossipData outMessage = new GossipData(inMessage);
        if(locals.doubleToggle == false) {
            System.out.println(locals);
        }
        if(inMessage.msgType == messageTypes.CONSOLE){
            //toggle this, will prevent a double print on the originating node
            locals.doubleToggle = !locals.doubleToggle;
        }else{
            //will be received from another node so need to pass along
            outMessage.originatorID = inMessage.nodeID; //set the orignator to the node we received from
            outMessage.targetPort +=  locals.nodeID - outMessage.originatorID; //send in the same direction i.e. propagate
        }
        outMessage.msgType = messageTypes.REQUEST;
 
        sendMsg(outMessage, outMessage.targetPort);
    }

    // p
    private void ping(GossipData inMessage){
        GossipData outMessage = new GossipData(inMessage);
        switch(inMessage.msgType){
            case CONSOLE:
                //treat this as normal
                outMessage.msgType = messageTypes.REQUEST;
                locals.replyFromPort = outMessage.targetPort;
                sendMsg(outMessage, outMessage.targetPort); 
                locals.startTransaction();
                break;
            case REPLY:
                System.out.print("#> Ping reply received from node: " + inMessage.nodeID);
                if(inMessage.nodeID > locals.nodeID){
                    System.out.println(", Node above is alive");
                }else{
                    System.out.println(", Node below is alive");
                }
                locals.resetTransaction();

                break;
            case REQUEST:
                outMessage.msgType = messageTypes.REPLY; //reply to requester
                //sets the targetPort to that of the node we received the message from
                // outMessage.targetPort += inMessage.originatorID - locals.nodeID;
                sendMsg(outMessage, outMessage.nodePort);
                break;
        }
    }

    private void networkAverage(GossipData inMessage){
        //console initiates
        GossipData outMessage = new GossipData(inMessage);
        if(locals.currentCycles == 0){
            //this would be the first time we are seeing this request
            locals.cyclesAtFirstRequest = locals.lifetimeCycles;
        }
        switch(inMessage.msgType){
            case CONSOLE:
                //start a transaction, send our value, and our cycle count
                outMessage.msgType = messageTypes.REQUEST;
                outMessage.sentValue = locals.dataValue;
                outMessage.cycleNumber = locals.currentCycles;
                System.out.println("#####CONSOLE: " + outMessage.targetPort);
                locals.replyFromPort = outMessage.targetPort;
                sendMsg(outMessage, outMessage.targetPort);
                locals.startTransaction();
                break;
            case REQUEST: 
                //only do something with this if we have not reached the cycle limit
                outMessage.msgType = messageTypes.REPLY; //prepare a reply
                outMessage.average = (inMessage.sentValue + locals.dataValue) / 2; //calc the average but do not save it locally yet
                System.out.println("#####REQUEST: " + inMessage.nodePort);
                locals.replyFromPort = outMessage.targetPort;
                sendMsg(outMessage, inMessage.nodePort);
                locals.startTransaction();
                break;
            case REPLY: 
                //we will receive the calculated average, back and need to send an ACK, and unlock
                outMessage.msgType = messageTypes.ACK;
                locals.currentAverage = inMessage.average;
                System.out.println("#####REPLY(" + locals.currentCycles + "): " + inMessage.nodePort);
                sendMsg(outMessage, inMessage.nodePort); //send back to requester
                locals.updateCycles();
                locals.resetTransaction();
                break;
            case ACK: 
                //receive the ACK, complete the transaction
                //we sent the average we calculated, but now we can save it with the receipt of the ACK
                locals.currentAverage = inMessage.average;
                locals.updateCycles();
                locals.resetTransaction();
                break;
        }
        if(locals.currentCycles >= locals.cycleLimit){
            System.out.println("NETWORK AVERAGE RESULTS: Average at Node " + locals.nodeID + " -> " + locals.currentAverage);
        }else if(inMessage.msgType == messageTypes.REPLY || inMessage.msgType == messageTypes.ACK){
            //we need to send again to add additional cycles
            //lets make a new request as if we were a console, to take advantage of the sendBothWays function
            outMessage.msgType = messageTypes.CONSOLE; //will trigger sendBothWays
            imitateConsole(outMessage);
            //the CONSOLE case will be called again and everything else will be taken care of
            System.out.println("#####propagating AVG");
        }
    }

    private void imitateConsole(GossipData outMessage){
        try{
            DatagramSocket DGSocket = new DatagramSocket();
            InetAddress IPAddress = InetAddress.getByName("localhost");
    
            //open a byte array output stream
            ByteArrayOutputStream byteoutStream = new ByteArrayOutputStream();
    
            //use the byte out stream to send the serialized gossipObj
            ObjectOutputStream outStream = new ObjectOutputStream(byteoutStream);
            outStream.writeObject(outMessage);
    
            //the serialized data object is converted and stored in a byte array
            //it is then placed into a datagram packet, and sent to the IPAddress and port
            byte[] data = byteoutStream.toByteArray();
            DatagramPacket sendPacket = new DatagramPacket(data, data.length, IPAddress, Gossip.serverPort);
            DGSocket.send(sendPacket);
            DGSocket.close();
        }catch (UnknownHostException UHE){
            System.out.println("\nCM: Unknown Host Exception.\n");
            UHE.printStackTrace();
        }catch (IOException IOE){
            IOE.printStackTrace();
        }

    }

    //v
    private void regenerateNetwork(GossipData inMessage){
        GossipData outMessage = new GossipData(inMessage);
        if(locals.doubleToggle == false) {
            locals.generateNewValue(true);
        }
        if(inMessage.msgType == messageTypes.CONSOLE){
            //toggle this, will prevent a double print on the originating node
            locals.doubleToggle = !locals.doubleToggle;
        }else{
            //will be received from another node so need to pass along
            outMessage.originatorID = inMessage.nodeID; //set the orignator to the node we received from
            outMessage.targetPort +=  locals.nodeID - outMessage.originatorID; //send in the same direction i.e. propagate
        }
        outMessage.msgType = messageTypes.REQUEST;
        sendMsg(outMessage, outMessage.targetPort);
    }

    private void sendMsg(GossipData message, int targetPort){
        //sends a gossipData message to the recipient node
        try{
            if(targetPort >= 48100){
                GossipData toSend = new GossipData(message);
                if(message.msgType == messageTypes.REQUEST){
                    message.transID = locals.createTransID();
                    locals.transactionQueue.put(message.transID);
                    locals.activeTransactionStatusMap.put(message.transID, message);
                }
        
                DatagramSocket DGSocket = new DatagramSocket();
                InetAddress IPAddress = InetAddress.getByName("localhost");
                ByteArrayOutputStream byteoutStream = new ByteArrayOutputStream();
                ObjectOutputStream outStream = new ObjectOutputStream(byteoutStream);

                message.nodeID = locals.nodeID;
                message.nodePort = locals.serverPort;
                outStream.writeObject(message);
                
                System.out.println("    Message sent -> Sender: " + locals.serverPort + ", to target: " + targetPort + " : " + message);

                byte[] data = byteoutStream.toByteArray();
                DatagramPacket sendPacket = new DatagramPacket(data, data.length, IPAddress, targetPort);
                DGSocket.send(sendPacket);
                DGSocket.close();
            }else{
                System.out.println("#> GDir: target port out of bounds, skipping send, " + targetPort);
            }
            
        }catch(UnknownHostException UNH){
            UNH.printStackTrace();
        }catch(IOException IOE){
            IOE.printStackTrace();
        }catch(Exception e){
            e.printStackTrace();
        }
    }
}


public class Gossip {
    public static int serverPort = 48100;
    public static void main(String[] args) throws Exception{
        int NodeNumber = 0; //THIS COMES FROM FIRST ARGUMENT PASSED
        if(args.length > 0){
            try{
                NodeNumber = Integer.parseInt(args[0]);

            }catch (NumberFormatException NFE){
                System.out.println("The only argument Gossip accepts is an integer number");
            }
        }
        serverPort += NodeNumber;
        NodeInfo nodeLocalInfo = new NodeInfo(NodeNumber, serverPort);
        System.out.println("Nicholas Ragano's Gossip Server 1.0 starting up, listening at port " + nodeLocalInfo.serverPort + ".");

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
            DatagramSocket DGListenerSocket = new DatagramSocket(nodeLocalInfo.serverPort);
            //create a byte buffer to hold incoming packets
            byte[] incomingData = new byte[2048]; //can accept a message of 1024 bytes
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
                GossipData gossipObj = new GossipData((GossipData) objInStream.readObject());
                    switch(gossipObj.msgType){
                        // CONSOLE -> consoleQueue
                        // REQUEST -> requestQueue
                        // REPLY && ACK -> conversationQueue
                        case CONSOLE: {
                            gossipObj.originatorID = nodeLocalInfo.nodeID;
                            switch(gossipObj.command){
                                case PING: sendBothWays(gossipObj, nodeLocalInfo.consoleQueue); break;
                                case NEWVAL: sendBothWays(gossipObj, nodeLocalInfo.consoleQueue); break;
                                case SHOWLOCALS: sendBothWays(gossipObj, nodeLocalInfo.consoleQueue); break;
                                case AVG: sendBothWays(gossipObj, nodeLocalInfo.consoleQueue); break;
                                default: nodeLocalInfo.consoleQueue.add(gossipObj); break;
                            }
                            break;
                        }
                        case REQUEST: {
                            System.out.println("-REQUEST- RECEIVED FROM NODE: " + gossipObj.nodeID + " : " + gossipObj);
                            switch(gossipObj.command){
                                case AVG: sendBothWays(gossipObj, nodeLocalInfo.requestQueue); break;
                                default: nodeLocalInfo.requestQueue.add(gossipObj); break;
                            }
                            
                            break;
                        }
                        case REPLY:{
                            System.out.println("-REPLY- RECEIVED FROM NODE: " + gossipObj.nodeID + " : " + gossipObj);
                            // nodeLocalInfo.conversationQueue.add(gossipObj); 
                            nodeLocalInfo.activeTransactionStatusMap.replace(gossipObj.transID, gossipObj); //stores the reply
                            break;
                        } 
                        case ACK: 
                            System.out.println("-ACK- RECEIVED FROM NODE: " + gossipObj.nodeID + " : " + gossipObj);
                            //finalize values, reset flags, service next request, manager should handle this
                            // nodeLocalInfo.conversationQueue.add(gossipObj); 
                            nodeLocalInfo.activeTransactionStatusMap.replace(gossipObj.transID, gossipObj);
                            break;
                        case NONE: break;
                        default: break;
                    }
            }
            //server clean up before end
            DGListenerSocket.close(); //close the socket before we shutdown
        } catch (SocketException SE){
            SE.printStackTrace();
        } catch (IOException IOE){
            IOE.printStackTrace();
        } catch(ClassNotFoundException CNF) {
            CNF.printStackTrace();
        }
    }

    public static void sendBothWays(GossipData message, BlockingQueue<GossipData> queueToPlaceIn){
        //we need to handle this specially since it is really two requests
        // one to the node above, and one to the node below
        GossipData clonedMsg = new GossipData(message);
        //turn this request into a node above request
        message.targetPort = serverPort + 1;
        // and the second into the node below
        clonedMsg.targetPort = serverPort - 1;
        queueToPlaceIn.add(message);
        queueToPlaceIn.add(clonedMsg);
        // System.out.println("    Sending both ways, Up: " + message + ", Down: " + clonedMsg);
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
                    System.out.println("#> CM: Exiting by user request.\n");
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
