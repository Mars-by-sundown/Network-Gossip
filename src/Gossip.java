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
import java.util.*;

import java.util.HashMap;

enum messageTypes {
    START,
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
    String GID; //gossipID
    int cycle = 0; //cycles processed for this gossip session AT THIS NODE
    boolean printed = false; //whether we have printed a result for this transaction yet
    boolean startSeen = false;
    boolean msgFromUp = false;
    boolean msgFromDown = false;
    commands gossipCmd = commands.NONE;
    long timeLastActive = System.currentTimeMillis();
}

class GossipData implements Serializable {
    float sentValue;
    boolean sentBool;
    String messageString;
    int highestVal; //highest value seen in the network
    int highestVal_ID;
    int lowestVal;  //lowest value seen in the network
    int lowestVal_ID;

    //sender information
    int senderID; //unique id of the sending node
    int senderPort; //port of the sending node

    //target & transmission information
    int targetPort; //destination

    //message information
    String transID; //id of the transaction this is a part of
    String gossipID; //id of the session this is a part of
    messageTypes msgType = messageTypes.NONE; //tracks RRA or console
    commands command = commands.NONE;  //holds what the command is

    public GossipData(){

    }

    public GossipData(GossipData copyFrom){
        this.sentValue = copyFrom.sentValue;
        this.sentBool = copyFrom.sentBool;
        this.messageString = copyFrom.messageString;
        this.highestVal = copyFrom.highestVal;
        this.highestVal_ID = copyFrom.highestVal_ID;
        this.lowestVal = copyFrom.lowestVal;
        this.lowestVal_ID = copyFrom.lowestVal_ID;

        this.senderID = copyFrom.senderID;
        this.senderPort = copyFrom.senderPort;
        
        this.targetPort = copyFrom.targetPort;

        this.transID = copyFrom.transID;
        this.gossipID = copyFrom.gossipID;
        this.msgType = copyFrom.msgType;
        this.command = copyFrom.command;
    }

    // public String toString(){
    //     return "Sender ID: " + this.nodeID + ", Msg: " + messageString;
    // }
}

class NodeInfo{
    //local values
    int nodeID;
    int serverPort;
    int dataValue; //random data value 
    int minNetworkVal;
    int minNetworkVal_ID;
    int maxNetworkVal;
    int maxNetworkVal_ID;
    float average; //average of the network
    float size; //calculate size of network with inverse of average
    int lifetimeCycles = 0; //lifetime count of cycles
    int cycleLimit = 20; //set to 20 be default
    
    boolean verboseMode = true;
    boolean nodeIsFree = true; //marked as false when we send a request or reply

    boolean updownToggle = false; //used to alternate direction of send
    boolean aboveOpen = true;
    boolean belowOpen = true;
    long resendTime = 750;


    BlockingQueue<String> gossipQueue;
    BlockingQueue<String> transactionQueue; //FIFO transaction queue, i.e. head is the current transID we care about
    HashMap<String, GossipData> transRecMap; //holds the last message we received for each transaction, incoming messages are stored here
    HashMap<String, GossipData> transSentMap; //holds the last message we sent for each transaction, sent messages are stored here
    //a map of the active gossip sessions
    //  key:gossipID, 
    //  value: a structure that holds info related to the gossip session, information is local to each node
    //      this should allow each node to keep track of how many times it has seen a piece of gossip
    //      and also allow it to track if it should process it, has printed the output, or just ignore it entirely 
    HashMap<String, gossipEntry> gossipMap; 

    NodeInfo(int id, int sp){
        nodeID = id;
        serverPort = sp;
        generateNewValue(false); //populate our dataValue with a random value on construction

        //at this moment in time we are unaware of any node except ourself so these are all "true"
        minNetworkVal = dataValue;
        minNetworkVal_ID = nodeID;
        maxNetworkVal_ID = nodeID;
        maxNetworkVal = dataValue;
        average = dataValue;
        size = 1;

        gossipQueue = new ArrayBlockingQueue<String>(5);
        transactionQueue = new ArrayBlockingQueue<String>(20);
        transRecMap = new HashMap<String, GossipData>();
        transSentMap = new HashMap<String, GossipData>();
        gossipMap = new HashMap<String, gossipEntry>();
    }


    public messageTypes typeOfLastReceived(String transID){
        //checks and returns the message type of the last received message with the current transID
        if(transactionQueue.isEmpty()){
            return messageTypes.NONE;
        }else{
            if(transRecMap.containsKey(transID)){
                //there is an entry
                return transRecMap.get(transID).msgType;
            }
        }
        return messageTypes.NONE;
    }

    public commands cmdOfLastReceived(String transID){
        //checks and returns the message type of the last received message with the current transID
        if(transactionQueue.isEmpty()){
            return commands.NONE;
        }else{
            if(transRecMap.containsKey(transID)){
                //there is an entry
                return transRecMap.get(transID).command;
            }
        }
        return commands.NONE;
    }

    public boolean isFirstOfSession(GossipData message){
        return !gossipMap.containsKey(message.gossipID);
    }

    public int getGossipCycles(GossipData message){
        return gossipMap.get(message.gossipID).cycle;
    }

    public void addGossipCycles(String GID, int cyclesToAdd){
        gossipEntry entry = gossipMap.get(GID);
        entry.cycle += cyclesToAdd;
        gossipMap.put(GID, entry);
    }

    public void setGossipPrinted(String GID){
        gossipEntry entry = gossipMap.get(GID);
        entry.printed = true;
        gossipMap.put(GID, entry);
    }

    public void setStartSeen(String GID){
        gossipEntry entry = gossipMap.get(GID);
        entry.startSeen = true;
        gossipMap.put(GID, entry);
    }

    public void updateGossipTime(String GID){
        gossipEntry entry = gossipMap.get(GID);
        entry.timeLastActive = System.currentTimeMillis();
        gossipMap.put(GID, entry);
    }

    public void updateGossipUpDownAwareness(GossipData message){
        gossipEntry entry = gossipMap.get(message.gossipID);
        if(message.senderPort > serverPort){
            entry.msgFromUp = true;
        }
        if(message.senderPort < serverPort){
            entry.msgFromDown= true;
        }
        gossipMap.put(message.gossipID, entry);
    }

    public boolean nodeSeenAbove(GossipData message){
        return gossipMap.get(message.gossipID).msgFromUp;
    }

    public boolean nodeSeenBelow(GossipData message){
        return gossipMap.get(message.gossipID).msgFromDown;
    }

    public int toggleUpDown(){
        updownToggle = !updownToggle;
        if(updownToggle){
            return 1;
        }else{
            return -1;
        }
    }


    public String createTransID(){
        StringBuilder retStr = new StringBuilder();
        retStr.append(lifetimeCycles);
        retStr.append(".");
        retStr.append(serverPort);
        return retStr.toString();
    }
    public void addToQueue(GossipData message){
        transactionQueue.add(message.transID);
    }

    public void startTransaction(){
        nodeIsFree = false;
    }

    public void endTransaction(){
        nodeIsFree = true;
    }

    public boolean isEmitter(){
        if(nodeID == 0 || nodeID % 2 == 0){
            return true;
        }else{
            return false;
        }
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
        average = dataValue;
        minNetworkVal = dataValue;
        maxNetworkVal = dataValue;
    }

    public int getSize(){
        if(size == 0){
            return 0;
        }else{
            return Math.round(1/size);
        }
    }

    public void sendMsg(GossipData message, int targetPort){
        //sends a gossipData message to the recipient node
        try{
            if(targetPort != serverPort){
                DatagramSocket DGSocket = new DatagramSocket();
                InetAddress IPAddress = InetAddress.getByName("localhost");
                ByteArrayOutputStream byteoutStream = new ByteArrayOutputStream();
                ObjectOutputStream outStream = new ObjectOutputStream(byteoutStream);
                message.senderID = nodeID;
                message.senderPort = serverPort;
                message.targetPort = targetPort;
                outStream.writeObject(message);
                if(verboseMode){
                    System.out.println(lifetimeCycles + "-->SENT(" + message.transID + "): " + message.msgType + ":" + message.command + "(" + message.sentValue + ") -> Sender: " + serverPort + ", Target: " + message.targetPort + " :GID " + message.gossipID);
                }
                byte[] data = byteoutStream.toByteArray();
                DatagramPacket sendPacket = new DatagramPacket(data, data.length, IPAddress, message.targetPort);
                DGSocket.send(sendPacket);
                transSentMap.put(message.transID, message);
                updateGossipTime(message.gossipID);
                DGSocket.close();
            }   
        }catch(UnknownHostException UNH){
            UNH.printStackTrace();
        }catch(IOException IOE){
            IOE.printStackTrace();
        }catch(Exception e){
            e.printStackTrace();
        }
    }

    public void forward(GossipData message){
        //forwards message same direction it was headed
        if(serverPort < 48109 && serverPort > 48100){
            sendMsg(message, serverPort + (serverPort - message.senderPort));
        }

    }

    public void emit(GossipData message, boolean forward){
        message.msgType = messageTypes.REQUEST;
        message.transID = createTransID();
        boolean sent = false;
        if(updownToggle && aboveOpen){
            //we are pointing up and its open
            sendMsg(message, serverPort + 1);
            sent = true;
        }else if(!updownToggle && belowOpen){
            //we are pointing down and its open
            sendMsg(message, serverPort - 1);
            sent = true;
        }
        if(sent == false && belowOpen){
            //we were pointing up but up was closed send down if we can
            sendMsg(message, serverPort - 1);
        }else if(sent == false && aboveOpen){
            //we were pointing down but down was closed, send up if we can
            sendMsg(message, serverPort + 1);
        }
        updownToggle = !updownToggle;
    }

    public GossipData sendBothWays(GossipData message){
        //clone the message
        //assign new transID
        //send both ways
        GossipData clonedMsg = new GossipData(message);
        if(serverPort < 48109){
            //original goes up
            sendMsg(message, serverPort + 1);
            lifetimeCycles++;
        }
        if(serverPort > 48100){
            //cloned goes down
            clonedMsg.transID = createTransID();
            sendMsg(clonedMsg, serverPort - 1);
        }

        return clonedMsg;
    }

    public void showQueueDetails(){
        System.out.println("Queue size: " + transactionQueue.size() + " node is free = " + nodeIsFree);
        System.out.println(transactionQueue.toString());
        Iterator queueIter = transactionQueue.iterator();
        while(queueIter.hasNext()){
            String nextItm = queueIter.next().toString();
            GossipData recMsg = transRecMap.get(nextItm);
            GossipData sentMsg = transSentMap.get(nextItm);
            System.out.print("####################################################################################################\n");
            if(recMsg != null){
                System.out.print(    "   Last Received: " + recMsg.msgType + ":" + recMsg.command + " received from: " + recMsg.senderPort +" -> transID::GossipID " + recMsg.transID + "::" + recMsg.gossipID + "\n");
            }else{
                System.out.print(    "   Last Received: " + null + ":" + null + " -> transID::GossipID " + null + "::" + null + "\n");
            }
            if(sentMsg != null){
                System.out.print(    "   Last Sent: " + sentMsg.msgType + ":" + sentMsg.command + " sent to: " + sentMsg.targetPort +" -> transID::GossipID " + sentMsg.transID + "::" + sentMsg.gossipID + "\n");
            }else{
                System.out.print(    "   Last Sent: " + null + ":" + null + " -> transID::GossipID " + null + "::" + null + "\n");
            }
            System.out.print("####################################################################################################\n");
        }
    }

    public String toString(){
        return 
        "####################################################################################################\n" +
        "   NodeID: " + nodeID + " port: " + serverPort + "\n" +
        "   node Values (local value, avg, min, max): (" + dataValue + ", " + average + ", " + minNetworkVal + ", " + maxNetworkVal + ")\n" +
        "   current size: " + size +
        "   cycle Info (limit, lifetime): (" + cycleLimit + ", " + lifetimeCycles + ")\n" +
        "####################################################################################################\n";
    }
}

class GossipDirector extends Thread{
    NodeInfo locals;
    boolean keepAlive = true;
    GossipData incomingData; //will be pulled from a queue
    String currentTransID;

    GossipDirector(NodeInfo n){
        this.locals = n;
    }

    public void run(){
        while(keepAlive){
            if(!locals.transactionQueue.isEmpty()){
                currentTransID = locals.transactionQueue.peek(); //get the current transID at the head of the queue
                incomingData = locals.transRecMap.get(currentTransID);
                if(!locals.gossipMap.containsKey(incomingData.gossipID)){
                    locals.aboveOpen = true;
                    locals.belowOpen = true;
                    System.out.println("CREATING NEW G - ENTRY");
                    gossipEntry newEntry = new gossipEntry();
                    newEntry.cycle = 0;
                    newEntry.GID = incomingData.gossipID;
                    newEntry.gossipCmd = incomingData.command;
                    locals.gossipMap.put(newEntry.GID, newEntry);
                    locals.gossipQueue.add(newEntry.GID);
                }
                locals.updateGossipUpDownAwareness(incomingData); //tracks if we have seen messages for this gossip session from above and below

                switch(locals.cmdOfLastReceived(currentTransID)){
                    case AVG: 
                        networkAverage(incomingData);
                        break;
                    case DELNODE:
                        break;
                    case KILLNET:
                        break;
                    case LIFETIME:
                        break;
                    case MINMAX:
                        networkMinMax(incomingData);
                        break;
                    case NEWLIMIT:
                        break;
                    case NEWVAL:
                        break;
                    case PING: 
                        ping(incomingData);
                        break;
                    case SHOWCOMMANDS:
                        displayAllCommands();
                        break;
                    case SHOWLOCALS:
                        displayLocals(incomingData);
                        break;
                    case SHOWQ: 
                        locals.showQueueDetails();
                        break;
                    case SIZE:
                        networkSize(incomingData);
                        break;
                    case VERBOSE:
                        break;
                    case NONE:
                        break;
                    default:
                        break;

                }
                // switch(locals.typeOfLastReceived(currentTransID)){
                //     case CONSOLE:
                //         //this is us originating a request
                //         //build the request
                //         //send it both directions
                //         //lock the node until ACK is sent
                //         break;
                //     case REQUEST:
                //         //we are receiving a request from another node
                //         //if its the first of this gossip ID
                //         //  update data values
                //         //  forward it
                //         //reply to this request
                //         //lock the node until ACK is received
                //         break;
                //     case REPLY:
                //         //we are receiving a reply to a request that we sent
                //         //update our values
                //         //build ACK
                //         //send ACK
                //         //if we are an even node and have not reached our cycle limit, send a request to opposite direction we just received from
                //         //complete transaction, unlock node for next transID
                //         break;
                //     case ACK:
                //         //we are receiving and ACK to our Reply
                //         //update our values
                //         //if we are an even node and have not reached our cycle limit, send a request to opposite direction we just received from
                //         //complete the transaction, unlock node for next transID
                //         break;
                //     case FIN:
                //         //we would receive this when the node we sent to has completed and is not accepting requests for this GID anymore
                //         //we would send this if we have reached our GID limit and receive a new request for that GID
                //         break;
                //     default:
                //         break;
                // }
            }else{
                //the transaction queue is empty, check that we dont have any incomplete gossip cycle
                if(!locals.gossipQueue.isEmpty() && locals.gossipMap.get(locals.gossipQueue.peek()).gossipCmd == commands.PING){
                    locals.gossipQueue.remove();
                }else if(!locals.gossipQueue.isEmpty() && locals.isEmitter()){
                    //then there is something there and we are an emitter
                    gossipEntry entry = locals.gossipMap.get(locals.gossipQueue.peek()); // get our entry
                    if(entry.cycle >= locals.cycleLimit){
                        locals.gossipQueue.remove(); //then this is a completed cycle
                    }else{
                        //there are cycles remaining
                        if((System.currentTimeMillis() - entry.timeLastActive) > locals.resendTime){
                            locals.lifetimeCycles++;
                            GossipData newMsg = new GossipData(); //create a new message
                            newMsg.command = entry.gossipCmd;
                            newMsg.msgType = messageTypes.REQUEST; //set request to drive action
                            newMsg.transID = locals.createTransID();
                            newMsg.gossipID = entry.GID;
                            System.out.println("HEARTBEAT(" + newMsg.gossipID + "): cycles-> " + entry.cycle + " transID: " + newMsg.transID);
                            if(locals.updownToggle){
                                locals.sendMsg(newMsg, locals.serverPort + 1);
                            }else{
                                locals.sendMsg(newMsg, locals.serverPort - 1);
                            }
                            locals.updownToggle = !locals.updownToggle;
                        }
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
        switch(locals.typeOfLastReceived(currentTransID)){
            case CONSOLE:
                System.out.println(locals);
                outMessage.msgType = messageTypes.REQUEST;
                locals.sendBothWays(outMessage);
                break;
            case REQUEST:
                //we are receiving a request from another node
                locals.forward(outMessage);
                System.out.println(locals);
                break;
            default:
                break;
        }
        locals.transactionQueue.remove();
        locals.gossipQueue.remove();
        locals.lifetimeCycles++;
    }


    // p
    private void ping(GossipData inMessage){
        GossipData outMessage = new GossipData(inMessage);
        switch(locals.typeOfLastReceived(currentTransID)){
            case CONSOLE:
                //this is us originating a request
                //build the request
                outMessage.msgType = messageTypes.REQUEST; //set REQUEST
                //send it both directions
                locals.sendBothWays(outMessage); //send both ways
                break;
            case REQUEST:
                //we are receiving a request from another node
                outMessage.msgType = messageTypes.REPLY; //set type to REPLY
                locals.sendMsg(outMessage, inMessage.senderPort); //return to sender
                locals.gossipQueue.remove();
                break;
            case REPLY:
                //we are receiving a reply to a request that we sent
                if(inMessage.senderID > locals.nodeID){
                    //reply from node above
                    System.out.println("Node above is alive");
                }else{
                    System.out.println("No response from node above");
                }
                if(inMessage.senderID < locals.nodeID){
                    System.out.println("Node below is alive");
                }else{
                    System.out.println("No response from node below");
                }
                break;
            default:
                break;
        }
        locals.transactionQueue.remove();

    }

    // z
    private void networkSize(GossipData inMessage){
        GossipData outMessage = new GossipData(inMessage);
        switch(locals.typeOfLastReceived(currentTransID)){
            case CONSOLE:
                locals.size = 1;
                //this is us originating a session
                outMessage.msgType = messageTypes.START; //set START
                //send it both directions
                locals.setStartSeen(outMessage.gossipID);
                locals.sendBothWays(outMessage); //send both ways

                //if we are an emitting node we can emit our request down
                if(locals.isEmitter()){
                    locals.lifetimeCycles++;
                    locals.emit(outMessage, false);
                }
                locals.transactionQueue.remove(); //remove this CONSOLE transaction from the queue
                break;

            case START:
                //we receive a start from another node
                if(!locals.gossipMap.get(inMessage.gossipID).startSeen){
                    //if its the first time we have a seen a START message for this gossipID
                    locals.size = 0;
                    locals.setStartSeen(inMessage.gossipID); //mark that we have seen one
                    //forward this START message
                    // outMessage.transID = locals.createTransID(); // make a new transID each time we forward the start
                    System.out.println("FORWARDING: " + outMessage.msgType + ":" + outMessage.command + " -- " + outMessage.transID);
                    locals.forward(outMessage); //forward the start message
                    //remove this START transaction from the queue
                    locals.transactionQueue.remove();

                    //if we are an emitter, then send our request down
                    if(locals.isEmitter()){
                        locals.emit(outMessage,false); //sends down
                    }
                }else{
                    //we have seen a START so just dump this one it has no use
                    System.out.println("Start from: " + inMessage.senderPort + " was dumped");
                    locals.transactionQueue.remove();
                }
                break;
            case REQUEST:
                if(locals.getGossipCycles(inMessage) < locals.cycleLimit){
                    if(locals.nodeIsFree){
                        //we can fulfill this request
                        outMessage.msgType = messageTypes.REPLY; //set reply
                        outMessage.sentValue = locals.size; //reply with our current size
                        locals.lifetimeCycles++;
                        locals.sendMsg(outMessage, inMessage.senderPort); // return to sender
                        locals.addGossipCycles(inMessage.gossipID, 1); //complete the gossip cycle
                        locals.nodeIsFree = false; //lock our node until we receive an ACK
                    }
                }else{
                    //tell them we are done and will not accept
                    outMessage.msgType = messageTypes.FIN;
                    outMessage.sentValue = locals.size; //send our average in case they can use it
                    locals.sendMsg(outMessage, inMessage.senderPort);
                    locals.transactionQueue.remove(); //clear it from the queue of it
                }
                break;
            case REPLY:
                //we are receiving a reply to a request that we sent some time ago
                locals.size = (inMessage.sentValue + locals.size) / 2; //get the new average and set our average to it
                outMessage.msgType = messageTypes.ACK; //set ACK
                outMessage.sentValue = locals.size; //send the new average back with the ACK
                locals.lifetimeCycles++; //add a cycle to lifetime
                locals.addGossipCycles(inMessage.gossipID, 1); //we have completed a gossip cycle at this node
                locals.sendMsg(outMessage, inMessage.senderPort);   //return response to sender
                locals.transactionQueue.remove(); //clear this transaction, we are done


                //if we are an emitter and can emit, then try and trade with other partner by using forward
                if(locals.isEmitter() && locals.getGossipCycles(outMessage) < locals.cycleLimit){
                    locals.emit(inMessage, true);
                }
                break;

            case ACK:
                //we are receiving and ACK to our Reply
                locals.size = inMessage.sentValue; //save the new average that was sent to us
                locals.nodeIsFree = true; //free our node
                // locals.addGossipCycles(inMessage.gossipID, 1); //complete the gossip cycle
                locals.transactionQueue.remove(); //remove this transID from the queue, its done

                //if we are an emitter then emit to our other neighbor
                if(locals.isEmitter() && locals.getGossipCycles(outMessage) < locals.cycleLimit){
                    locals.emit(inMessage, true); 
                }

                break;

            case FIN:
                //we got a FIN, so this transaction isnt going to happen
                if(inMessage.senderPort > locals.serverPort){
                    //close node above us
                    locals.aboveOpen = false;
                }
                if(inMessage.senderPort < locals.serverPort){
                    //close the node below us
                    locals.belowOpen = false;
                }
                if((!locals.aboveOpen && !locals.belowOpen) || (!locals.nodeSeenAbove(inMessage) || !locals.nodeSeenBelow(inMessage))){
                    //if both above and below are closed OR we have not yet seen a message from one of the directions then finish the cycle at this node
                    gossipEntry entry = locals.gossipMap.get(inMessage.gossipID);
                    entry.cycle = locals.cycleLimit;
                    locals.gossipMap.put(entry.GID, entry);
                    System.out.println("FORCED COMPLETION");

                    //some nodes, particularly on the ends may end up getting very few cycles before they can no longer communicate.
                    //so if our average is close then leave it but if its way off then take the senders average
                    if((Math.abs(inMessage.sentValue - locals.size) / locals.size) < .75){
                        //if the average of the ABS(finished node - our node) divided by our nodes value is < .5 then switch to theirs 
                        locals.size = inMessage.sentValue;
                    }
                }
                locals.transactionQueue.remove(); //remove the fin
                locals.nodeIsFree = true; //free our node if not already free
                break;
        }
        if(locals.getGossipCycles(inMessage) >= locals.cycleLimit && !locals.gossipMap.get(inMessage.gossipID).printed){
            System.out.println(
                "\n####################################################################################################\n" +
                "   " + "Size at node(value): " + locals.nodeID + "(" + locals.dataValue + ") = " + locals.getSize() +
                "\n####################################################################################################\n");
            locals.setGossipPrinted(inMessage.gossipID); //set that we have printed the output
            locals.aboveOpen = true;
            locals.belowOpen = true;
            locals.gossipQueue.remove();
        }
    }

    // a
    private void networkAverage(GossipData inMessage){
        GossipData outMessage = new GossipData(inMessage);
        switch(locals.typeOfLastReceived(currentTransID)){
            case CONSOLE:
                locals.average = locals.dataValue; //reset our average
                //this is us originating a session
                outMessage.msgType = messageTypes.START; //set START
                //send it both directions
                locals.sendBothWays(outMessage); //send both ways

                //if we are an emitting node we can emit our request down
                if(locals.isEmitter()){
                    locals.lifetimeCycles++;
                    locals.emit(outMessage, false);
                }
                locals.transactionQueue.remove(); //remove this CONSOLE transaction from the queue
                break;

            case START:
                //we receive a start from another node
                if(!locals.gossipMap.get(inMessage.gossipID).startSeen){
                    //if its the first time we have a seen a START message for this gossipID
                    locals.average = locals.dataValue; //reset our average
                    locals.setStartSeen(inMessage.gossipID); //mark that we have seen one
                    //forward this START message
                    // outMessage.transID = locals.createTransID(); // make a new transID each time we forward the start
                    System.out.println("FORWARDING: " + outMessage.msgType + ":" + outMessage.command + " -- " + outMessage.transID);
                    locals.forward(outMessage); //forward the start message
                    //remove this START transaction from the queue
                    locals.transactionQueue.remove();

                    //if we are an emitter, then send our request down
                    if(locals.isEmitter()){
                        locals.emit(outMessage,false); //sends down
                    }
                }else{
                    //we have seen a START so just dump this one it has no use
                    System.out.println("Start from: " + inMessage.senderPort + " was dumped");
                    locals.transactionQueue.remove();
                }
                break;
            case REQUEST:
                if(locals.getGossipCycles(inMessage) < locals.cycleLimit){
                    if(locals.nodeIsFree){
                        //we can fulfill this request
                        outMessage.msgType = messageTypes.REPLY; //set reply
                        outMessage.sentValue = locals.average; //reply with our current AVG
                        locals.lifetimeCycles++;
                        locals.sendMsg(outMessage, inMessage.senderPort); // return to sender
                        locals.addGossipCycles(inMessage.gossipID, 1); //we have completed a gossip cycle at this node
                        locals.nodeIsFree = false; //lock our node until we receive an ACK
                    }
                }else{
                    //tell them we are done and will not accept
                    outMessage.msgType = messageTypes.FIN;
                    outMessage.sentValue = locals.average; //send our average in case they can use it
                    locals.sendMsg(outMessage, inMessage.senderPort);
                    locals.transactionQueue.remove(); //clear it from the queue of it
                }
                break;
            case REPLY:
                //we are receiving a reply to a request that we sent some time ago
                locals.average = (inMessage.sentValue + locals.average) / 2; //get the new average and set our average to it
                outMessage.msgType = messageTypes.ACK; //set ACK
                outMessage.sentValue = locals.average; //send the new average back with the ACK
                locals.lifetimeCycles++; //add a cycle to lifetime
                locals.addGossipCycles(inMessage.gossipID, 1); //we have completed a gossip cycle at this node
                locals.sendMsg(outMessage, inMessage.senderPort);   //return response to sender
                locals.transactionQueue.remove(); //clear this transaction, we are done


                //if we are an emitter and can emit, then try and trade with other partner by using forward
                if(locals.isEmitter() && locals.getGossipCycles(outMessage) < locals.cycleLimit){
                    locals.emit(inMessage, true);
                }
                break;

            case ACK:
                //we are receiving and ACK to our Reply
                locals.average = inMessage.sentValue; //save the new average that was sent to us
                locals.nodeIsFree = true; //free our node
                // locals.addGossipCycles(inMessage.gossipID, 1); //complete the gossip cycle
                locals.transactionQueue.remove(); //remove this transID from the queue, its done

                //if we are an emitter then emit to our other neighbor
                if(locals.isEmitter() && locals.getGossipCycles(outMessage) < locals.cycleLimit){
                    locals.emit(inMessage, true); 
                }

                break;

            case FIN:
                //we got a FIN, so this transaction isnt going to happen
                if(inMessage.senderPort > locals.serverPort){
                    //close node above us
                    locals.aboveOpen = false;
                }
                if(inMessage.senderPort < locals.serverPort){
                    //close the node below us
                    locals.belowOpen = false;
                }
                if((!locals.aboveOpen && !locals.belowOpen) || (!locals.nodeSeenAbove(inMessage) || !locals.nodeSeenBelow(inMessage))){
                    //if both above and below are closed OR we have not yet seen a message from one of the directions then finish the cycle at this node
                    gossipEntry entry = locals.gossipMap.get(inMessage.gossipID);
                    entry.cycle = locals.cycleLimit;
                    locals.gossipMap.put(entry.GID, entry);
                    System.out.println("FORCED COMPLETION");

                    //some nodes, particularly on the ends may end up getting very few cycles before they can no longer communicate.
                    //so if our average is close then leave it but if its way off then take the senders average
                    if((Math.abs(inMessage.sentValue - locals.average) / locals.average) < .75){
                        //if the average of the ABS(finished node - our node) divided by our nodes value is < .5 then switch to theirs 
                        locals.average = inMessage.sentValue;
                    }
                }
                locals.transactionQueue.remove(); //remove the fin
                locals.nodeIsFree = true; //free our node if not already free
                break;
        }
        if(locals.getGossipCycles(inMessage) >= locals.cycleLimit && !locals.gossipMap.get(inMessage.gossipID).printed){
            System.out.println(
                "\n####################################################################################################\n" +
                "   " + "Average at node(value): " + locals.nodeID + "(" + locals.dataValue + ") = " + locals.average +
                "\n####################################################################################################\n");
            locals.setGossipPrinted(inMessage.gossipID); //set that we have printed the output
            locals.aboveOpen = true;
            locals.belowOpen = true;
            locals.gossipQueue.remove();
        }
    }

    // z
    private void networkMinMax(GossipData inMessage){
        GossipData outMessage = new GossipData(inMessage);
        switch(locals.typeOfLastReceived(currentTransID)){
            case CONSOLE:
                //this is us originating a session
                outMessage.msgType = messageTypes.START; //set START
                //send it both directions
                locals.setStartSeen(outMessage.gossipID);
                locals.sendBothWays(outMessage); //send both ways

                //if we are an emitting node we can emit our request down
                if(locals.isEmitter()){
                    locals.lifetimeCycles++;
                    locals.emit(outMessage, false);
                }
                locals.transactionQueue.remove(); //remove this CONSOLE transaction from the queue
                break;

            case START:
                //we receive a start from another node
                if(!locals.gossipMap.get(inMessage.gossipID).startSeen){
                    //if its the first time we have a seen a START message for this gossipID
                    locals.setStartSeen(inMessage.gossipID); //mark that we have seen one
                    //forward this START message
                    // outMessage.transID = locals.createTransID(); // make a new transID each time we forward the start
                    System.out.println("FORWARDING: " + outMessage.msgType + ":" + outMessage.command + " -- " + outMessage.transID);
                    locals.forward(outMessage); //forward the start message
                    //remove this START transaction from the queue
                    locals.transactionQueue.remove();

                    //if we are an emitter, then send our request down
                    if(locals.isEmitter()){
                        locals.emit(outMessage,false); //sends down
                    }
                }else{
                    //we have seen a START so just dump this one it has no use
                    System.out.println("Start from: " + inMessage.senderPort + " was dumped");
                    locals.transactionQueue.remove();
                }
                break;
            case REQUEST:
                if(locals.getGossipCycles(inMessage) < locals.cycleLimit){
                    if(locals.nodeIsFree){
                        //we can fulfill this request
                        outMessage.msgType = messageTypes.REPLY; //set reply
                        outMessage.lowestVal = locals.minNetworkVal;
                        outMessage.lowestVal_ID = locals.minNetworkVal_ID;
                        outMessage.highestVal = locals.maxNetworkVal;
                        outMessage.highestVal_ID = locals.maxNetworkVal_ID;
                        locals.lifetimeCycles++;
                        locals.sendMsg(outMessage, inMessage.senderPort); // return to sender
                        locals.addGossipCycles(inMessage.gossipID, 1); //complete the gossip cycle
                        locals.nodeIsFree = false; //lock our node until we receive an ACK
                    }
                }else{
                    //tell them we are done and will not accept
                    outMessage.msgType = messageTypes.FIN;
                    outMessage.lowestVal = locals.minNetworkVal;
                    outMessage.lowestVal_ID = locals.minNetworkVal_ID;
                    outMessage.highestVal = locals.maxNetworkVal;
                    outMessage.highestVal_ID = locals.maxNetworkVal_ID;
                    locals.sendMsg(outMessage, inMessage.senderPort);
                    locals.transactionQueue.remove(); //clear it from the queue of it
                }
                break;
            case REPLY:
                //we are receiving a reply to a request that we sent some time ago
                if(inMessage.lowestVal < locals.minNetworkVal){
                    locals.minNetworkVal = inMessage.lowestVal;
                    locals.minNetworkVal_ID = inMessage.lowestVal_ID;
                }
                if(inMessage.highestVal > locals.maxNetworkVal){
                    locals.maxNetworkVal = inMessage.highestVal;
                    locals.maxNetworkVal_ID = inMessage.highestVal_ID;
                }
                outMessage.msgType = messageTypes.ACK; //set ACK
                outMessage.lowestVal = locals.minNetworkVal;
                outMessage.lowestVal_ID = locals.minNetworkVal_ID;
                outMessage.highestVal = locals.maxNetworkVal;
                outMessage.highestVal_ID = locals.maxNetworkVal_ID;
                locals.lifetimeCycles++; //add a cycle to lifetime
                locals.addGossipCycles(inMessage.gossipID, 1); //we have completed a gossip cycle at this node
                locals.sendMsg(outMessage, inMessage.senderPort);   //return response to sender
                locals.transactionQueue.remove(); //clear this transaction, we are done


                //if we are an emitter and can emit, then try and trade with other partner by using forward
                if(locals.isEmitter() && locals.getGossipCycles(outMessage) < locals.cycleLimit){
                    locals.emit(inMessage, true);
                }
                break;

            case ACK:
                //we are receiving and ACK to our Reply
                locals.minNetworkVal = inMessage.lowestVal;
                locals.minNetworkVal_ID = inMessage.lowestVal_ID;
                locals.maxNetworkVal = inMessage.highestVal;
                locals.maxNetworkVal_ID = inMessage.highestVal_ID;
                locals.nodeIsFree = true; //free our node
                // locals.addGossipCycles(inMessage.gossipID, 1); //complete the gossip cycle
                locals.transactionQueue.remove(); //remove this transID from the queue, its done

                //if we are an emitter then emit to our other neighbor
                if(locals.isEmitter() && locals.getGossipCycles(outMessage) < locals.cycleLimit){
                    locals.emit(inMessage, true); 
                }

                break;

            case FIN:
                //we got a FIN, so this transaction isnt going to happen
                if(inMessage.senderPort > locals.serverPort){
                    //close node above us
                    locals.aboveOpen = false;
                }
                if(inMessage.senderPort < locals.serverPort){
                    //close the node below us
                    locals.belowOpen = false;
                }
                if((!locals.aboveOpen && !locals.belowOpen) || (!locals.nodeSeenAbove(inMessage) || !locals.nodeSeenBelow(inMessage))){
                    //if both above and below are closed OR we have not yet seen a message from one of the directions then finish the cycle at this node
                    gossipEntry entry = locals.gossipMap.get(inMessage.gossipID);
                    entry.cycle = locals.cycleLimit;
                    locals.gossipMap.put(entry.GID, entry);
                    System.out.println("FORCED COMPLETION");

                }
                locals.transactionQueue.remove(); //remove the fin
                locals.nodeIsFree = true; //free our node if not already free
                break;
        }
        if(locals.getGossipCycles(inMessage) >= locals.cycleLimit && !locals.gossipMap.get(inMessage.gossipID).printed){
            System.out.println(
                "\n####################################################################################################\n" +
                "   " + "Min-Max at node " + locals.nodeID + "\n" +
                "       " + "Max: node " + locals.maxNetworkVal_ID + " = " + locals.maxNetworkVal + "\n" +
                "       " + "Min: node " + locals.minNetworkVal_ID + " = " + locals.minNetworkVal + "\n" +
                "\n####################################################################################################\n");
            locals.setGossipPrinted(inMessage.gossipID); //set that we have printed the output
            locals.aboveOpen = true;
            locals.belowOpen = true;
            locals.gossipQueue.remove();
        }
    }

    // // v
    // private void regenerateNetwork(GossipData inMessage){
    //     GossipData outMessage = new GossipData(inMessage);
    //     if(locals.doubleToggle == false) {
    //         locals.generateNewValue(true);
    //     }
    //     if(inMessage.msgType == messageTypes.CONSOLE){
    //         //toggle this, will prevent a double print on the originating node
    //         locals.doubleToggle = !locals.doubleToggle;
    //     }else{
    //         //will be received from another node so need to pass along
    //         outMessage.targetPort +=  locals.nodeID - inMessage.nodeID; //send in the same direction i.e. propagate
    //     }
    //     outMessage.msgType = messageTypes.REQUEST;
    //     sendMsg(outMessage, outMessage.targetPort);
    //     locals.resetTransaction(locals.doubleToggle);
    // }
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
        NodeInfo locals = new NodeInfo(NodeNumber, serverPort);
        System.out.println("Nicholas Ragano's Gossip Server 1.0 starting up, listening at port " + locals.serverPort + ".");

        //Start a thread for our Gossip Director
        GossipDirector GDir = new GossipDirector(locals);
        Thread GdirThread = new Thread(GDir);
        GdirThread.start(); 

        //Start a thread for the ConsoleMonitor to listen for console commands
        ConsoleMonitor CM = new ConsoleMonitor();
        Thread CMThread = new Thread(CM);
        CMThread.start();
        try{
            //create our datagram listener socket
            DatagramSocket DGListenerSocket = new DatagramSocket(locals.serverPort);
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
                GossipData inMessage = new GossipData((GossipData) objInStream.readObject());
                if(locals.verboseMode){
                    System.out.println(locals.lifetimeCycles + "<--RECEIVED(" + inMessage.transID + "): " + inMessage.msgType + ":" + inMessage.command 
                    + "(" + inMessage.sentValue +") FROM NODE: " + inMessage.senderID + " :GID " + inMessage.gossipID);
                }
                if(inMessage.msgType != messageTypes.NONE && inMessage.msgType != null){
                    //place it in the queue to be handled by the director
                    if(inMessage.command == commands.SHOWQ){
                        locals.showQueueDetails();
                    }else{
                        if(inMessage.msgType == messageTypes.CONSOLE){
                            //if its a console message we need to attach some IDs
                            inMessage.transID = locals.createTransID();
                            inMessage.gossipID = locals.createTransID();
                        }
                    locals.transRecMap.put(inMessage.transID, inMessage);
                    locals.transactionQueue.add(inMessage.transID);

                    }
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
                System.out.println("#> CM: Enter a string to send to the gossipServer, or type quit/stopserver: ");
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
