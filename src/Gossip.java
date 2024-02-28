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
    FIN, 
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
    SHOWQ,
    VERBOSE,
    NONE
}

class gossipEntry{
    String ID;
    int cycle;
    boolean printed = false;
    boolean finReceived = false;
    long timeOfLastService;
}

class GossipData implements Serializable {
    int sentValue;
    float sentFloat;
    int highestVal; //highest value seen in the network
    int highestVal_ID;
    int lowestVal;  //lowest value seen in the network
    int lowestVal_ID;

    int targetPort; 
    int nodeID; //unique id of the sending node
    int nodePort; //port of the sending node

    String transID; //id of the transaction this is a part of
    String gossipID; //id of the session this is a part of
    int cycleNumber = 0;
    int retries = 0;
    boolean boolVal = false;
    messageTypes msgType = messageTypes.NONE; //tracks RRA or console
    commands command = commands.NONE;  //holds what the command is

    String messageString;

    public GossipData(){
    }

    public GossipData(GossipData copyFrom){
        this.sentValue = copyFrom.sentValue;
        this.sentFloat = copyFrom.sentFloat;
        this.highestVal = copyFrom.highestVal;
        this.highestVal_ID = copyFrom.highestVal_ID;
        this.lowestVal = copyFrom.lowestVal;
        this.lowestVal_ID = copyFrom.lowestVal_ID;
        this.targetPort = copyFrom.targetPort;
        this.nodeID = copyFrom.nodeID;
        this.nodePort = copyFrom.nodePort;
        this.msgType = copyFrom.msgType;
        this.command = copyFrom.command;
        this.cycleNumber = copyFrom.cycleNumber;
        this.transID = copyFrom.transID;
        this.gossipID = copyFrom.gossipID;
        this.boolVal = copyFrom.boolVal;
    }

    // public String toString(){
    //     return "Sender ID: " + this.nodeID + ", Msg: " + messageString;
    // }
}

class NodeInfo{
    int nodeID;
    int serverPort;
    int dataValue; //random data value 
    int minNetworkVal;
    int minNetworkVal_ID;
    int maxNetworkVal;
    int maxNetworkVal_ID;
    int currentAverage; //average of the network
    float currentSize = 0; //calculate size of network with inverse of average
    int lifetimeCycles = 0; //lifetime count of cycles
    int currentCycles = 0;
    int cycleLimit = 10; //set to 20 be default
    
    boolean verboseMode = true;
    //conversation tracking
    boolean nodeIsFree = true;
    int replyFromPort;
    long timeLastSent;
    long timeoutTime = 1500;
    boolean doubleToggle = false;
    boolean updownToggle = false;

    BlockingQueue<String> transactionQueue; //FIFO transaction queue, i.e. head is the current transaction we care about
    HashMap<String, GossipData> transLastReceivedMap; //map of the last received packet for each transaction
    HashMap<String, GossipData> transLastSentMap; //map of the last packet we sent for each transaction

    //a map of the active gossip sessions
    //  key:gossipID, 
    //  value: a structure that holds info related to the gossip session, information is local to each node
    //      this should allow each node to keep track of how many times it has seen a piece of gossip
    //      and also allow it to track if it should process it, has printed the output, or just ignore it entirely 
    HashMap<String, gossipEntry> gossipSessionMap; 

    NodeInfo(int id, int sp){
        nodeID = id;
        serverPort = sp;
        generateNewValue(false); //populate our dataValue with a random value on construction
        //at this moment in time we are unaware of any node except ourself so these are all "true"
        minNetworkVal = dataValue;
        maxNetworkVal = dataValue;
        currentAverage = dataValue;

        transactionQueue = new ArrayBlockingQueue<String>(20);
        transLastReceivedMap = new HashMap<String, GossipData>();
        transLastSentMap = new HashMap<String, GossipData>();
        gossipSessionMap = new HashMap<String, gossipEntry>();
    }

    public messageTypes storedTransType(){
        //checks and returns the message type of the last received message with the current transID
        if(transactionQueue.isEmpty() || !transLastReceivedMap.containsKey(transactionQueue.peek())){
            return messageTypes.NONE;
        }
        return transLastReceivedMap.get(transactionQueue.peek()).msgType;
    }

    public int toggleUpDown(){
        updownToggle = !updownToggle;
        if(updownToggle){
            return 1;
        }else{
            return -1;
        }
    }

    public String startNewGossipCycle(){
        gossipEntry info = new gossipEntry();
        info.ID = createTransID();
        info.cycle = 0;
        info.timeOfLastService = System.currentTimeMillis();
        gossipSessionMap.put(info.ID, info);
        return info.ID;
    }

    public boolean allowService(String ID){
        if(gossipSessionMap.containsKey(ID)){
            //we have seen messages from this gossip session before
            if(!gossipSessionMap.get(ID).printed){
                return true;
            }else if(gossipSessionMap.get(ID).cycle >= cycleLimit){
                //ignore as we have reached out limit
                return false;
            }
        }else{
            //this is the first message we have received from this session
            gossipEntry info = new gossipEntry();
            info.ID = ID;
            info.cycle = 0; //local count of times seen
            info.timeOfLastService = System.currentTimeMillis();
            gossipSessionMap.put(ID, info);
        }
        return true;
    }

    public void cleanQueue(GossipData comMessage){
        //sends the com message to all in the queue for this gossipID
        //cleans the transQueue of all completed gossipID
        if(!transactionQueue.isEmpty()){
            //get the first item of the transaction queue
            String idToRemove = comMessage.gossipID;
            String currentHead = transactionQueue.remove();
            transactionQueue.add(currentHead);
            String tempItem;
            while(currentHead != transactionQueue.peek()){
                tempItem = transactionQueue.remove(); //take the element
                if(transLastReceivedMap.containsKey(tempItem) && transLastReceivedMap.get(tempItem).gossipID == idToRemove){
                    //the key exists and we dont want it in here
                }else{
                    transactionQueue.add(tempItem);
                }
            }
            transactionQueue.remove();
        }

        
    }

    public void setGossipPrinted(String id){
        gossipEntry entry = gossipSessionMap.get(id);
        entry.printed = true;
        gossipSessionMap.put(id, entry);
    }

    public String createTransID(){
        StringBuilder retStr = new StringBuilder();
        retStr.append(lifetimeCycles);
        retStr.append(".");
        retStr.append(serverPort);
        return retStr.toString();
    }

    public String originateNewTransaction(GossipData message, boolean orginator){
        if(orginator){
            message.transID = createTransID(); //make a transID
        }
        transactionQueue.add(message.transID); //place in transaction queue for processing
        transLastReceivedMap.put(message.transID, message); //create an entry in the transaction map to hold replies
        return message.transID;
    }

    public void startTransaction(){
        nodeIsFree = false;
        timeLastSent = System.currentTimeMillis();
    }

    public boolean waitTimeExceeded(){
        //returns true if we have waited longer than the timeoutTime since receiving a reply
        return ((System.currentTimeMillis() - timeLastSent) > timeoutTime);
    }

    public void resetTransaction(boolean setToggle){
        nodeIsFree = true;
        doubleToggle = setToggle;
        //clear this item from the head of the queue, and erase its history
        transLastReceivedMap.remove(transactionQueue.remove());
    }

    public void updateCycles(String id){
        gossipEntry temp = gossipSessionMap.get(id);
        temp.cycle++;
        gossipSessionMap.put(id, temp);
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
        currentAverage = dataValue;
        minNetworkVal = dataValue;
        maxNetworkVal = dataValue;
        
    }

    public int getSize(){
        if(currentSize == 0){
            return 0;
        }else{
            return Math.round(1/currentSize);
        }

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
            incomingData = null;
            if(locals.nodeIsFree){
                //we can start a new tranaction
                if(locals.storedTransType() == messageTypes.CONSOLE || locals.storedTransType() == messageTypes.REQUEST || locals.storedTransType() == messageTypes.FIN){
                    incomingData = locals.transLastReceivedMap.get(locals.transactionQueue.peek());
                    switch(incomingData.command){
                        case AVG: networkAverage(incomingData); break;
                        case DELNODE:  break;
                        case KILLNET:  break;
                        case LIFETIME:  break;
                        case MINMAX:  break;
                        case NEWLIMIT:  break;
                        case NEWVAL: regenerateNetwork(incomingData); break;
                        case NONE:  break;
                        case PING: ping(incomingData); break;
                        case SHOWCOMMANDS: displayAllCommands(); break;
                        case SHOWLOCALS: displayLocals(incomingData); break;
                        case SIZE:  networkSize(incomingData); break;
                        default:  break;
                    }
                }
            }else{
                //we first look for REPLY or ACK messages for our transaction
                //if none are found then check that we havent timed out and loop
                if(locals.storedTransType() == messageTypes.REPLY || locals.storedTransType() == messageTypes.ACK || locals.storedTransType() == messageTypes.FIN){
                    //the current transaction has received a reply
                    incomingData = locals.transLastReceivedMap.get(locals.transactionQueue.peek());
                    switch(incomingData.command){
                        case AVG: networkAverage(incomingData); break;
                        case DELNODE:  break;
                        case KILLNET:  break;
                        case LIFETIME:  break;
                        case MINMAX:  break;
                        case NEWLIMIT:  break;
                        case NEWVAL: regenerateNetwork(incomingData); break;
                        case NONE:  break;
                        case PING: ping(incomingData); break;
                        case SHOWCOMMANDS: break; //NO OP
                        case SHOWLOCALS: displayLocals(incomingData); break;
                        case SIZE:  networkSize(incomingData); break;
                        default:  break;
                    }
                }else if(locals.waitTimeExceeded()){
                    //the wait time has been exceeded. 
                    //  dump the transaction and go service console and request queues
                    if(locals.verboseMode){
                        System.out.println("transQueue: " + locals.transactionQueue.toString());
                    }
                    GossipData lastSent = locals.transLastSentMap.get(locals.transactionQueue.peek()); //get the last message we sent
                    if(lastSent != null){
                        if(locals.verboseMode){
                            System.out.println("!!!!!WAIT TIME EXCEEDED!!!!!!!");
                            System.out.println("    Failed to receive reply to our " + lastSent.msgType +  " message from: " + lastSent.targetPort + ", transID -> " + lastSent.transID +" :GID" + lastSent.gossipID);
                        }
                        if((lastSent.msgType == messageTypes.REQUEST)){
                            //try and send the opposite direction
                            lastSent.targetPort = locals.serverPort + (locals.serverPort - lastSent.targetPort);
                        }

                        //resend the last message
                        if(lastSent.retries < 3){
                            lastSent.retries += 1;
                            if(locals.verboseMode){
                                System.out.println("RESENDING: " + lastSent.transID + " to: " + lastSent.targetPort);
                            }

                            sendMsg(lastSent, lastSent.targetPort);
                        }else{
                            //drop this one
                            if(locals.verboseMode){
                                System.out.println("    retry limit reached, aborting transaction attempt");
                            }
                            locals.nodeIsFree = true;
                        }
                        locals.resetTransaction(false);
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
            outMessage.targetPort +=  locals.nodeID - inMessage.nodeID; //send in the same direction i.e. propagate
        }
        outMessage.msgType = messageTypes.REQUEST;
        sendMsg(outMessage, outMessage.targetPort);
        locals.resetTransaction(locals.doubleToggle);
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

                locals.resetTransaction(false);
                break;
            case REQUEST:
                outMessage.msgType = messageTypes.REPLY; //reply to requester
                //sets the targetPort to that of the node we received the message from
                sendMsg(outMessage, outMessage.nodePort);
                locals.resetTransaction(false);
                break;
        }
    }

    private void networkAverage(GossipData inMessage){
        GossipData outMessage = new GossipData(inMessage);
        if(locals.allowService(inMessage.gossipID)){
            switch(inMessage.msgType){
                case CONSOLE:
                    //we are starting a gossip session, we will always be able to service these
                    outMessage.msgType = messageTypes.REQUEST;//send a request
                    outMessage.sentValue = locals.currentAverage; //send our current average
                    sendMsg(outMessage, inMessage.targetPort);
                    locals.startTransaction();
                    break;
    
                case REQUEST:
                    //we receive a avg request from a node
                    //if we are at our limit, reply that we are done for this session with FIN
                    //else reply as normal
                    outMessage.msgType = messageTypes.REPLY;
                    outMessage.sentValue = (locals.currentAverage + inMessage.sentValue) / 2; //send back new average
                    sendMsg(outMessage, inMessage.nodePort); //send back to requester
                    locals.startTransaction();
                    break;

                case REPLY:
                    //we receive the reply to our request
                    locals.currentAverage = inMessage.sentValue; // save the new average
                    outMessage.msgType = messageTypes.ACK; //send an ACK
                    locals.updateCycles(inMessage.gossipID); //update our counts
                    sendMsg(outMessage, inMessage.nodePort); //send our ACK
                    locals.resetTransaction(false); //close this transaction on our end
                    break;

                case ACK:
                    //we receive an ACK to our REPLY
                    locals.currentAverage = inMessage.sentValue; //we can now save the average we calculated
                    locals.updateCycles(inMessage.gossipID); //update our cycles
                    locals.resetTransaction(false); //close this transaction on our end
                    break;

                case FIN:
                    //the node we tried requesting from has finished, so we should print our result too, and be done
                    gossipEntry entry = locals.gossipSessionMap.get(inMessage.gossipID);
                    entry.cycle = locals.cycleLimit;
                    locals.gossipSessionMap.put(inMessage.gossipID, entry);

                    locals.resetTransaction(false);
                    break;
            }
        }else{
            //we reached the limit and are not allowing service, send a FIN to whoever sent this request
            outMessage.msgType = messageTypes.FIN;
            outMessage.sentValue = locals.currentAverage;
            outMessage.nodeID = locals.nodeID;
            outMessage.nodePort = locals.serverPort;
            sendMsg(outMessage, inMessage.nodePort);
            locals.resetTransaction(false);
        }

        //whether we allow service of this gossip message or not check the status of the session its related to
        if(!locals.gossipSessionMap.get(inMessage.gossipID).printed && locals.gossipSessionMap.get(inMessage.gossipID).cycle >= locals.cycleLimit){
            //we have not yet printed a response, and our limit has been reached
            System.out.println(
                "\n####################################################################################################\n" +
                "   " + "Average at node(value): " + locals.nodeID + "(" + locals.dataValue + ") = " + locals.currentAverage +
                "\n####################################################################################################\n");
            locals.setGossipPrinted(inMessage.gossipID); //set that we have printed the output
            locals.cleanQueue(inMessage);
            // locals.resetTransaction(false); //remove this transaction from the 
        }
        if(inMessage.msgType == messageTypes.REPLY || inMessage.msgType == messageTypes.ACK){
            //resend this out if we have not reached the cycle limit
            if(locals.gossipSessionMap.get(outMessage.gossipID).cycle < locals.cycleLimit){
                GossipData nextMessage = new GossipData(outMessage);
                nextMessage.msgType = messageTypes.CONSOLE;
                nextMessage.sentValue = locals.currentAverage;
                nextMessage.nodeID = locals.nodeID;
                nextMessage.nodePort = locals.serverPort;
                nextMessage.targetPort = locals.serverPort + locals.toggleUpDown();
                locals.originateNewTransaction(nextMessage, true); 
            }else{
                outMessage.msgType = messageTypes.FIN;
                outMessage.sentValue = locals.currentAverage;
                outMessage.nodeID = locals.nodeID;
                outMessage.nodePort = locals.serverPort;
                sendMsg(outMessage, inMessage.nodePort);
            }
        }
    }

    // z
    private void networkSize(GossipData inMessage){
        GossipData outMessage = new GossipData(inMessage);
        if(locals.allowService(inMessage.gossipID)){
            switch(inMessage.msgType){
                case CONSOLE:
                    //we are starting a gossip session, we will always be able to service these
                    outMessage.msgType = messageTypes.REQUEST;//send a request
                    outMessage.sentFloat = locals.currentSize; //send our current size of 1 as the initiator
                    sendMsg(outMessage, inMessage.targetPort);
                    locals.startTransaction();
                    break;
    
                case REQUEST:
                    //we receive a avg request from a node
                    //if we are at our limit, reply that we are done for this session with FIN
                    //else reply as normal
                    outMessage.msgType = messageTypes.REPLY;
                    outMessage.sentFloat = (locals.currentSize + inMessage.sentFloat) / 2; //send back new average
                    sendMsg(outMessage, inMessage.nodePort); //send back to requester
                    locals.startTransaction();
                    break;

                case REPLY:
                    //we receive the reply to our request
                    locals.currentSize = inMessage.sentFloat; // save the new average
                    outMessage.msgType = messageTypes.ACK; //send an ACK
                    locals.updateCycles(inMessage.gossipID); //update our counts
                    sendMsg(outMessage, inMessage.nodePort); //send our ACK
                    locals.resetTransaction(false); //close this transaction on our end
                    break;

                case ACK:
                    //we receive an ACK to our REPLY
                    locals.currentSize = inMessage.sentFloat; //we can now save the average we calculated
                    locals.updateCycles(inMessage.gossipID); //update our cycles
                    locals.resetTransaction(false); //close this transaction on our end
                    break;

                case FIN:
                    //the node we tried requesting from has finished, so we should print our result too, and be done
                    gossipEntry entry = locals.gossipSessionMap.get(inMessage.gossipID);
                    entry.cycle = locals.cycleLimit;
                    locals.gossipSessionMap.put(inMessage.gossipID, entry);

                    locals.resetTransaction(false);
                    break;
            }
        }else{
            //we reached the limit and are not allowing service, send a FIN to whoever sent this request
            outMessage.msgType = messageTypes.FIN;
            outMessage.sentFloat = locals.currentSize;
            outMessage.nodeID = locals.nodeID;
            outMessage.nodePort = locals.serverPort;
            sendMsg(outMessage, inMessage.nodePort);
            locals.resetTransaction(false);
        }

        //whether we allow service of this gossip message or not check the status of the session its related to
        if(!locals.gossipSessionMap.get(inMessage.gossipID).printed && locals.gossipSessionMap.get(inMessage.gossipID).cycle >= locals.cycleLimit){
            //we have not yet printed a response, and our limit has been reached
            System.out.println(
                "\n####################################################################################################\n" +
                "   " + "Calculated network size at node: " + locals.nodeID + " = " + locals.getSize() +
                "\n####################################################################################################\n");
            locals.setGossipPrinted(inMessage.gossipID); //set that we have printed the output
            locals.cleanQueue(inMessage);
            // locals.resetTransaction(false); //remove this transaction from the 
        }
        if(inMessage.msgType == messageTypes.REPLY || inMessage.msgType == messageTypes.ACK){
            //resend this out if we have not reached the cycle limit
            if(locals.gossipSessionMap.get(outMessage.gossipID).cycle < locals.cycleLimit){
                GossipData nextMessage = new GossipData(outMessage);
                nextMessage.msgType = messageTypes.CONSOLE;
                nextMessage.sentFloat = locals.currentSize;
                nextMessage.nodeID = locals.nodeID;
                nextMessage.nodePort = locals.serverPort;
                nextMessage.targetPort = locals.serverPort + locals.toggleUpDown();
                locals.originateNewTransaction(nextMessage, true); 
            }else{
                outMessage.msgType = messageTypes.FIN;
                outMessage.sentFloat = locals.currentSize;
                outMessage.nodeID = locals.nodeID;
                outMessage.nodePort = locals.serverPort;
                sendMsg(outMessage, inMessage.nodePort);
            }
        }
    }

    // v
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
            outMessage.targetPort +=  locals.nodeID - inMessage.nodeID; //send in the same direction i.e. propagate
        }
        outMessage.msgType = messageTypes.REQUEST;
        sendMsg(outMessage, outMessage.targetPort);
        locals.resetTransaction(locals.doubleToggle);
    }

    private void sendMsg(GossipData message, int targetPort){
        //sends a gossipData message to the recipient node
        try{
            if(targetPort != locals.serverPort){
                DatagramSocket DGSocket = new DatagramSocket();
                InetAddress IPAddress = InetAddress.getByName("localhost");
                ByteArrayOutputStream byteoutStream = new ByteArrayOutputStream();
                ObjectOutputStream outStream = new ObjectOutputStream(byteoutStream);
                locals.lifetimeCycles++;
                message.nodeID = locals.nodeID;
                message.nodePort = locals.serverPort;
                message.targetPort = targetPort;
                outStream.writeObject(message);
                if(locals.verboseMode){
                    System.out.println("-->SENT(" + message.sentFloat + "): " + message.msgType + ":" + message.command + "(" + message.transID + ") -> Sender: " + locals.serverPort + ", Target: " + message.targetPort + " :GID " + message.gossipID);
                }
                locals.transLastSentMap.put(message.transID, message);
                byte[] data = byteoutStream.toByteArray();
                DatagramPacket sendPacket = new DatagramPacket(data, data.length, IPAddress, message.targetPort);
                DGSocket.send(sendPacket);
                
                DGSocket.close();
            // }else{
                // System.out.println("#> GDir: target port out of bounds, skipping send, " + targetPort);
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
                nodeLocalInfo.lifetimeCycles++;
                if(nodeLocalInfo.verboseMode){
                    System.out.println("<--RECEIVED(" + gossipObj.sentFloat + "): " + gossipObj.msgType + ":" + gossipObj.command + "(" + gossipObj.transID +") FROM NODE: " + gossipObj.nodeID + " :GID " + gossipObj.gossipID);
                }

                switch(gossipObj.msgType){
                    // CONSOLE -> consoleQueue
                    // REQUEST -> requestQueue
                    // REPLY && ACK -> conversationQueue
                    case CONSOLE: {
                        
                        if(!nodeLocalInfo.gossipSessionMap.containsKey(gossipObj.gossipID)){
                            gossipObj.gossipID = nodeLocalInfo.startNewGossipCycle();
                            System.out.println("SETTING CURRENTSIZE TO ONE");
                            nodeLocalInfo.currentSize = 1;
                        }
                        //we are orginating this request, sendBothWays handles calls to orignate a new transaction
                        switch(gossipObj.command){
                            case PING: sendBothWays(gossipObj, nodeLocalInfo, true); break;
                            case NEWVAL: sendBothWays(gossipObj, nodeLocalInfo, true); break;
                            case SHOWLOCALS: sendBothWays(gossipObj, nodeLocalInfo, true); break;
                            case SHOWQ:System.out.println(nodeLocalInfo.transactionQueue.toString()); break;
                            case AVG: 
                                gossipObj.targetPort = nodeLocalInfo.serverPort + nodeLocalInfo.toggleUpDown();
                                nodeLocalInfo.originateNewTransaction(gossipObj, true); 
                                break;
                            case SIZE:
                                gossipObj.targetPort = nodeLocalInfo.serverPort + nodeLocalInfo.toggleUpDown();
                                nodeLocalInfo.originateNewTransaction(gossipObj, true);
                                break;
                            default: nodeLocalInfo.originateNewTransaction(gossipObj, true); break;
                        }
                        break;
                    }
                    case REQUEST: {

                        //we receive a request, put in queue of transactions to process
                        switch(gossipObj.command){
                            case AVG: 
                                nodeLocalInfo.originateNewTransaction(gossipObj, false);
                                break;
                            case SIZE: 
                                if(!nodeLocalInfo.gossipSessionMap.containsKey(gossipObj.gossipID)){
                                    System.out.println("SETTING CURRENTSIZE TO ZERO");
                                    nodeLocalInfo.currentSize = 0;
                                }
                                nodeLocalInfo.originateNewTransaction(gossipObj, false);
                                break;
                            default: nodeLocalInfo.originateNewTransaction(gossipObj, false); break;
                        }
                        break;
                    }
                    case REPLY:{
                        //we receive a reply, place the message with its transID key for retrieval when its transaction is called(if not current)
                        // nodeLocalInfo.conversationQueue.add(gossipObj); 
                        nodeLocalInfo.transLastReceivedMap.replace(gossipObj.transID, gossipObj); //stores the reply
                        break;
                    } 
                    case ACK: 
                        //similarly place the ACK message with its transID key for processing when ready (could be ready now even)
                        //finalize values, reset flags, service next request, manager should handle this
                        // nodeLocalInfo.conversationQueue.add(gossipObj); 
                        nodeLocalInfo.transLastReceivedMap.replace(gossipObj.transID, gossipObj);
                        break;
                    case NONE: break;
                    case FIN: 
                        if(!nodeLocalInfo.gossipSessionMap.get(gossipObj.gossipID).finReceived){
                            //we have not received a FIN for this gossipID yet
                            gossipEntry temp = nodeLocalInfo.gossipSessionMap.get(gossipObj.gossipID);
                            temp.finReceived = true;
                            nodeLocalInfo.gossipSessionMap.put(gossipObj.gossipID, temp);                            
                            switch(gossipObj.command){
                                case AVG: 
                                    nodeLocalInfo.originateNewTransaction(gossipObj, false);
                                    break;
                                case SIZE: 
                                    nodeLocalInfo.originateNewTransaction(gossipObj, false);
                                    break;
                                default:  
                                    break;
                            }
                        }else{
                            //we have seen it before
                            if(nodeLocalInfo.verboseMode){
                                System.out.println("!!!FIN already seen for GID: " + gossipObj.gossipID + ", ignoring packet " + gossipObj.transID);
                            }
                            nodeLocalInfo.cleanQueue(gossipObj);
                        }
                        break;
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

    public static void sendBothWays(GossipData message, NodeInfo locals, boolean originator){
        //we need to handle this specially since it is really two requests
        // one to the node above, and one to the node below
        GossipData clonedMsg = new GossipData(message);
        //turn this request into a node above request
        message.targetPort = (locals.serverPort + 1);
        message.transID = locals.originateNewTransaction(message, originator);

        // and the second into the node below
        clonedMsg.targetPort = (locals.serverPort - 1);
        locals.lifetimeCycles++;
        clonedMsg.transID  = locals.originateNewTransaction(clonedMsg, originator);
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
                        case "q": gossipObj.command = commands.SHOWQ; break;
                        default: 
                            gossipObj.command = commands.NONE;
                            gossipObj.msgType = messageTypes.NONE;
                            System.out.println("Unrecognized argument passed to GossipWorker");
                            break;
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
