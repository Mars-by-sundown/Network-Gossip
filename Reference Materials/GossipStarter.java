/* Version 1.0 (watch for bugs), 2023-03-02: 

Clark Elliott, GossipStarter.java copyright (c) 2023 with all rights reserved.

Here is some basic utility code to get you started on your Network Gossip Protocol system.

FEATURES:

1. Translation from Java Object to byte array, to datagram, over network, back to byte array, to Java Object.
2. UDP/Datagram listener.
3. UDP/Datagram sending.
4. Two threads: one listening for datagrams and one scanning for user console input.
5. UDP Listener loop fires off a worker to handle the incoming Java object.

Note that with three (or more) threads sharing one console file for output, console messages may
be in a scrambled order.

Java version 19.0.1 (change as appropriate!)

Compile: javac GossipStarter.java

*/

import java.io.*;
import java.net.*;

class GossipData implements Serializable{ // Must be serializable to send 1 bit after another over the network.
  int nodeNumber;
  int average;
  int highValue;
  int lowValue;
  String userString;
  // Whatever else you might want to send.
}

class GossipWorker extends Thread {    // Class definition. Many of these worker threads may run simultaneously.
  GossipData gossipObj;                         // Class member, gossipObj, local to GossipWorker.
  GossipWorker (GossipData c) {gossipObj = c;}  // Constructor, assign arg c to local object

  public void run(){ // Do whatever you like here:
    System.out.println("\nGW: In Gossip worker: " + gossipObj.userString + "\n");
  }
}

public class GossipStarter {
  public static int serverPort = 45565; // Port number as a class variable
  public static int NodeNumber = 0;     // Will have to get this from the fist argument passed.
  
  public static void main(String[] args) throws Exception {
    System.out.println
      ("Clark Elliott's Gossip Server 1.0 starting up, listening at port " + GossipStarter.serverPort + ".\n");
    
    ConsoleLooper CL = new ConsoleLooper(); // create a DIFFERENT thread
    Thread t = new Thread(CL);
    t.start();  // ...and start it, waiting for console input
    
    boolean loopControl = true; // Keeps the datagram listener running.

    try{
      DatagramSocket DGSocket = new DatagramSocket(GossipStarter.serverPort);
      // Careful: you may have so many datagrams flying around you loose some for lack of buffer size.
      System.out.println("SERVER: Receive Buffer size: " + DGSocket.getReceiveBufferSize() + "\n");  
      byte[] incomingData = new byte[1024];
      InetAddress IPAddress = InetAddress.getByName("localhost");
      
      while (loopControl) { // Use control-C to manually terminate the server.
        DatagramPacket incomingPacket = new DatagramPacket(incomingData, incomingData.length);
        DGSocket.receive(incomingPacket);
        byte[] data = incomingPacket.getData();
        ByteArrayInputStream in = new ByteArrayInputStream(data);
        ObjectInputStream is = new ObjectInputStream(in);
        try {
          GossipData gossipObj = (GossipData) is.readObject();
          if (gossipObj.userString.indexOf("stopserver") > -1){
            System.out.println("SERVER: Stopping UDP listener now.\n");
            loopControl = false;
          }
          
          System.out.println("\nSERVER: Gossip object received = " + gossipObj.userString + "\n");
          new GossipWorker(gossipObj).start(); // Spawn a worker thread to handle it. Listen for more.
        } catch (ClassNotFoundException e) {
          e.printStackTrace();
        }
      }
    } catch (SocketException e) {
      e.printStackTrace();
    } catch (IOException i) {
      i.printStackTrace();
    }
  }
}

class ConsoleLooper implements Runnable {

  public void run(){ // RUNning the Console listen loop
    System.out.println("CL: In the Console Looper Thread");
    
    BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
    try {
      String someString;
      do {
        System.out.print
          ("CL: Enter a string to send to the gossipServer, (or, quit/stopserver): ");
        System.out.flush ();
        someString = in.readLine ();

        if (someString.indexOf("quit") > -1){
          System.out.println("CL: Exiting now by user request.\n");
          System.exit(0); // Ugly way to stop. You can fix with a more elegant throw.
        }

        // Trigger some action by sending a datagram packet to ourself:
        try{
          System.out.println("CL: Preparing the datagram packet now...");
          DatagramSocket DGSocket = new DatagramSocket();
          InetAddress IPAddress = InetAddress.getByName("localhost");
          
          GossipData gossipObj = new GossipData();
          gossipObj.userString = someString;
          
          ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
          ObjectOutputStream os = new ObjectOutputStream(outputStream);
          os.writeObject(gossipObj);
          byte[] data = outputStream.toByteArray();
          DatagramPacket sendPacket = new DatagramPacket(data, data.length, IPAddress, GossipStarter.serverPort);
          DGSocket.send(sendPacket);
          System.out.println("CL: Datagram has been sent.");
        } catch (UnknownHostException UH){
          System.out.println("\nCL: Unknown Host problem.\n"); // Test by commenting out / uncommenting out above.
          UH.printStackTrace();
        }
      } while (true);
    } catch (IOException x) {x.printStackTrace ();}
  }
}

/*----------------------------  SAMPLE OUTPUT: ----------------------------------


java GossipStarter

Clark Elliott's Gossip Server 1.0 starting up, listening at port 45565.

CL: In the Console Looper Thread
CL: Enter a string to send to the gossipServer, (or, quit/stopserver): SERVER: Receive Buffer size: 65536

Hello Gossip Network!

CL: Preparing the datagram packet now...
CL: Datagram has been sent.
CL: Enter a string to send to the gossipServer, (or, quit/stopserver): 
SERVER: Gossip object received = Hello Gossip Network!

GW: In Gossip worker: Hello Gossip Network!

Nice to talk with you, worker!

CL: Preparing the datagram packet now...
CL: Datagram has been sent.
CL: Enter a string to send to the gossipServer, (or, quit/stopserver): 
SERVER: Gossip object received = Nice to talk with you, worker!

GW: In Gossip worker: Nice to talk with you, worker!

stopserver

CL: Preparing the datagram packet now...
CL: Datagram has been sent.
CL: Enter a string to send to the gossipServer, (or, quit/stopserver): SERVER: Stopping UDP listener now.

SERVER: Gossip object received = stopserver

GW: In Gossip worker: stopserver

quit

CL: Exiting now by user request.

--------------------------------------------------------------------------------*/
