/*TODO:
    -Change Base ServerPort
    -Grab nodeNumber from arg pass
    -handle different console commands
    -implement queuing for incoming commands, must finish trades and calcs before beginning the next one
*/

import java.io.*;
import java.net.*;


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
    int numOfCycles_total = 0; //default of 20 cycles, can be changed from CmdLine

    int nodeSemaphore = 0; //true means it is locked
    boolean livingNodeAbove = false;
    boolean livingNodeBelow = false;
}

class GossipWorker extends Thread{
    GossipData gossipObject;

    GossipWorker(GossipData c) {
        gossipObject = c; //save reference to the object handed in
    }

    public void run(){
        System.out.println("\n Gossip Worker");
    }
}


public class Gossip {
    public static int serverPort = 48100; //THIS NEEDS TO CHANGE
    public static int NodeNumber = 0; //THIS COMES FROM FIRST ARGUMENT PASSED

    public static void main(String[] args) throws Exception{
        if(args.length == 1){
            System.out.println(args[0]);
            try{
                NodeNumber = Integer.parseInt(args[0]);
            }catch (NumberFormatException NFE){
                System.out.println("The only argument Gossip accepts is an integer number");
            }
            
        }
        serverPort += NodeNumber;
        System.out.println("Nicholas Ragano's Gossip Server 1.0 starting up, listening at port " + Gossip.serverPort + ".\n");

        //Start a thread for the ConsoleMonitor to listen for console commands
        ConsoleMonitor CM = new ConsoleMonitor();
        Thread CMThread = new Thread(CM);
        CMThread.start();

        boolean keepAlive = true; //keep the datagram listener running
        try{
            //create our datagram listener socket
            DatagramSocket DGListenerSocket = new DatagramSocket(Gossip.serverPort);
            System.out.println("SERVER: Receive Buffer size: " + DGListenerSocket.getReceiveBufferSize() + "\n");
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
                    new GossipWorker(gossipObj).start();

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
