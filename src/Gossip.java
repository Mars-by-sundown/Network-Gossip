/*TODO:
    -Change Base ServerPort
    -Grab nodeNumber from arg pass
    -handle different console commands
    -implement queuing for incoming commands, must finish trades and calcs before beginning the next one
*/

import java.io.*;
import java.net.*;
import java.util.Random;

import org.w3c.dom.Node;


class GossipData implements Serializable {
    int nodeID; //unique id of the sending node
    int average; //current average of network
    int highestVal; //highest value seen in the network
    int lowestVal;  //lowest value seen in the network

    boolean isGossipOriginator; //true if originator of the gossip session

    String consoleInputString;
}

class NodeInfo{
    int nodeID;
    int serverPort;
    int dataValue; //random data value 
    int minNetworkVal;
    int maxNetworkVal;
    int currentAverage; //average of the network
    int currentSize = 0; //calculate size of network with inverse of average
    int numOfCycles_total = 0; //lifetime count of cycles
    int cycleLimit = 20; //set to 20 be default

    int nodeSemaphore = 0; //true means it is locked
    boolean livingNodeAbove = false;
    boolean livingNodeBelow = false;

    NodeInfo(int id, int serverPort){
        this.nodeID = id;
        this.serverPort = serverPort;
        generateNewValue(); //populate our dataValue with a random value on construction

        //at this moment in time we are unaware of any node except ourself so these are all "true"
        minNetworkVal = dataValue;
        maxNetworkVal = dataValue;
        currentAverage = dataValue;
    }

    public void generateNewValue(){
        Random rng = new Random();
        dataValue = rng.nextInt(100);
    }
}

class GossipWorker extends Thread{
    GossipData gossipObject;
    NodeInfo localNode;

    GossipWorker(GossipData c, NodeInfo n) {
        gossipObject = c; //save reference to the object handed in
        localNode = n;
    }

    public void run(){
        System.out.println("\nGossip Worker " + gossipObject.consoleInputString);
        if(gossipObject.consoleInputString.matches("\\d+")){
            //if its an integer number
            setCycleLimit(Integer.parseInt(gossipObject.consoleInputString));
        }
        //else its something
        switch(gossipObject.consoleInputString){
            case "t": displayAllCommands(); break;
            case "l": displayLocals(); break;
            case "p": ping(); break;
            case "m": getNetwork_MinMax(); break;
            case "a": getNetwork_Average(); break;
            case "z": getNetwork_Size(); break;
            case "v": seedNewValues(); break;
            case "d": killSelf(false); break;
            case "k": killAll(); break;
            case "y": getCycleCount(); break;
            default: System.out.println("Unrecognized argument passed to GossipWorker");
        }
    }

    //t
    private void displayAllCommands(){
        System.out.println("Showing all commands.\n");
    }

    // l
    private void displayLocals(){
        System.out.println("-l display locals on all nodes. Node Value: <current node value>.\n");
        
    }

    // p
    private void ping(){
        if(isNodeAbove()){
            System.out.println("There is a node above, Node ID: " + (localNode.nodeID + 1) + ".\n");
        }else{
            System.out.println("No node found above.\n");
        }
        if(isNodeBelow()){
            System.out.println("There is a node below, Node ID: " + (localNode.nodeID - 1) + ".\n");
        }else{
            System.out.println("No node found below.\n");
        }
    }

    private boolean isNodeAbove(){
        return false;
    }

    private boolean isNodeBelow(){
        return false;
    }

    private boolean checkForNeighbor(int direction){
        return false;
    }

    //m
    private void getNetwork_MinMax(){
        System.out.println("The max of the network is: " + localNode.maxNetworkVal +".\n");
        System.out.println("The min of the network is: " + localNode.minNetworkVal +".\n");
    }

    //a
    private void getNetwork_Average(){
        System.out.println("The average value of the network is: " + localNode.currentAverage + ".\n");
    }

    //z
    private void getNetwork_Size(){
        System.out.println("The size of the network is: " + localNode.currentSize + ".\n");
    }

    //v
    private void seedNewValues(){
        System.out.println("Regenerating Node values.\n");
        //need to propogate to the rest of the network
    }

    //d
    private void killSelf(boolean propagate){
        //will need to send to next node in the chain and wait for a kill success in order to kill self
        System.out.println("Closing this node...\n");
        System.out.println("Killing listeners...\n");
    }

    //k
    private void killAll(){
        //send kill commands to the whole network
    }

    //y
    private void getCycleCount(){
        //return lifetime cycle count
        System.out.println("Lifetime Cycles: " + localNode.numOfCycles_total + ".\n");
    }

    //N (an integer # is input)
    private void setCycleLimit(int newLimit){
        System.out.println("Setting cycle limit of network to: " + newLimit + ".\n");
    }

    //Q
    private void getSizeOfSubnet(boolean isOriginator){
        //non-required
        if(isOriginator){
            //marks this node as the orginator
            //sends a count up and a count down
            //receives a number in return with count of nodes above and below in the subnet
        }else{
            //this would be activated if it was a message received from another Node
            //in this case it will check if there is a node in the opposite direction the request was received from
            //it will pass the message along and wait for a reply
            //it will then combine the count received and pass back to the requester
        }
    }


    
}


public class Gossip {
    public static int serverPort = 48100; //THIS NEEDS TO CHANGE
    public static int NodeNumber = 0; //THIS COMES FROM FIRST ARGUMENT PASSED
    public static NodeInfo nodeLocalInfo;

    public static void main(String[] args) throws Exception{
        if(args.length == 1){
            System.out.println(args[0]);
            try{
                NodeNumber = Integer.parseInt(args[0]);
                nodeLocalInfo = new NodeInfo(NodeNumber, serverPort);
            }catch (NumberFormatException NFE){
                System.out.println("The only argument Gossip accepts is an integer number");
            }
            
        }
        serverPort += NodeNumber;
        System.out.println("Nicholas Ragano's Gossip Server 1.0 starting up, listening at port " + Gossip.serverPort + ".");

        //Start a thread for the ConsoleMonitor to listen for console commands
        ConsoleMonitor CM = new ConsoleMonitor();
        Thread CMThread = new Thread(CM);
        CMThread.start();

        boolean keepAlive = true; //keep the datagram listener running
        try{
            //create our datagram listener socket
            DatagramSocket DGListenerSocket = new DatagramSocket(Gossip.serverPort);
            // System.out.println("SERVER: Receive Buffer size: " + DGListenerSocket.getReceiveBufferSize());
            //create a byte buffer to hold incoming packets
            byte[] incomingData = new byte[1024]; //can accept a message of 1024 bytes
            InetAddress IPAddress = InetAddress.getByName("localhost"); //not currently utilized

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

                    //check what the console input was, if it was a stop server command then die
                    if(gossipObj.consoleInputString.indexOf("stopserver") > -1){
                        System.out.println("SERVER: Stopping UDP listener now.\n");
                        keepAlive = false;
                    }
                    //take the message we received and fire up a worker to handle it
                    System.out.println("SERVER: Gossip command received: " + gossipObj.consoleInputString + "\n");
                    new GossipWorker(gossipObj, nodeLocalInfo).start();

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

                if(inString.indexOf("quit") > -1){
                    //user requested to quit
                    System.out.println("CM: Exiting by user request.\n");
                    //call to quit process
                    keepAlive = false;
                }

                try{
                    System.out.println("CM: Preparing the datagram packet now...");
                    DatagramSocket DGSocket = new DatagramSocket();
                    InetAddress IPAddress = InetAddress.getByName("localhost");

                    GossipData gossipObj = new GossipData();
                    gossipObj.consoleInputString = inString;

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
