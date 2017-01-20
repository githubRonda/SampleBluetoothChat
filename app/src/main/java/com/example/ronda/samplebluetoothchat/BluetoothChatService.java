package com.example.ronda.samplebluetoothchat;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;

import com.socks.library.KLog;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.util.UUID;

/**
 * This class does all the work for setting up and managing Bluetooth
 * connections with other devices. It has a thread that listens for
 * incoming connections, a thread for connecting with a device, and a
 * thread for performing data transmissions when connected.
 * <p>
 * 1. 这个类要启动（调用 start()）的前提要保证蓝牙是开着的状态，否则 BluetoothAdapter 就为 null，在 AcceptThread 中就会报空指针异常
 * 2. 这个类中共有3个线程：AcceptThread、ConnectThread 和 ConnectedThread。一开始调用 start() 方法的话是开启 AcceptThread 线程。因为开始时，我们是不知道当前设备是作为服务端还是客户端的，
 * -- 所以先开启 AcceptThread 来监听请求连接的 socket，若该设备要作为客户端来连接其他设备，则会在 connect() 方法中来关闭 AcceptThread 线程
 * 3. 本类中的状态变化依次是：STATE_NONE(调用构造器或stop()方法或连接动作出错或读写数据中断时) --> STATE_LISTEN(调用start()方法时) --> STATE_CONNECTING(调用 connect() 方法时) --> STATE_CONNECTED(调用connected()方法时)
 * -- 其实连接操作 mmSocket.connect(); 是在 ConnectThread 中执行的。
 * 4. 本类虽然是 BluetoothChatService ，但是和 android 系统组件 Service 是没有任何关系的。 BluetoothChatService 就是一个普通的类，这个类中 包含了 三个线程。
 */
public class BluetoothChatService {

    // Name for the SDP record when creating server socket
    private static final String NAME_SECURE = "BluetoothChatSecure";

    // Unique UUID for this application
    private static final UUID MY_UUID_SECURE = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    // Member fields
    private final BluetoothAdapter mBtAdapter;
    private final Handler          mHandler;  // 由 UI Activity 通过构造器传过来的

    private AcceptThread    mSecureAcceptThread; // 监听连接请求的线程。（因为此时还不知道谁作为客户端，谁作为服务端，所以此线程是最先启动的）
    private ConnectThread   mConnectThread;      // 连接操作的线程（因为连接操作是阻塞式的）。此线程是当在设备列表中选择一个远程的蓝牙设备进行连接时启动的。并且当内部的连接操作成功后，会自动置空此线程
    private ConnectedThread mConnectedThread;    // 读写数据的线程。此线程是在 ConnectThread 中连接操作成功后启动的，并且此时会关闭并置空其他两个线程。 注意：这时可以关闭 ConnectThread 中创建的 Socket 吗
    private int             mState;              // 记录本类中的状态变化

    // Constants that indicate the current connection state
    public static final int STATE_NONE       = 0; // we're doing nothing
    public static final int STATE_LISTEN     = 1; // now listening for incoming connections
    public static final int STATE_CONNECTING = 2; // now initiating an outgoing connection
    public static final int STATE_CONNECTED  = 3; // now connected to a remote device


    public BluetoothChatService(Handler handler) {
        mBtAdapter = BluetoothAdapter.getDefaultAdapter();
        mState = STATE_NONE;
        mHandler = handler;
    }

    private synchronized void setState(int state) {
        mState = state;

        // Give the new state to the Handler so the UI Activity can update
        mHandler.obtainMessage(Constants.MESSAGE_STATE_CHANGE, state, -1).sendToTarget();
    }

    private synchronized void setState(int state, String deviceName) {
        mState = state;

        // Give the new state to the Handler so the UI Activity can update
        mHandler.obtainMessage(Constants.MESSAGE_STATE_CHANGE, state, -1, deviceName).sendToTarget();
    }

    /**
     * Return the current connection state.
     */
    public synchronized int getState() {
        return mState;
    }

    /**
     * Start the chat service. Specifically start AcceptThread to begin a
     * session in listening (server) mode. Called by the Activity onResume()
     * <p>
     * start() 方法作用：先取消并置空连接线程(ConnectThread)、通讯线程(ConnectedThread)，然后启动服务端监听线程(AcceptThread)
     */
    public void start() {
        // Cancel any thread attempting to make a connection
        if (mConnectThread != null) {
            mConnectThread.cancel();
            mConnectThread = null;
        }

        // Cancel any thread currently running a connection
        if (mConnectedThread != null) {
            mConnectedThread.cancel();
            mConnectedThread = null;
        }

        setState(STATE_LISTEN);

        KLog.d("start --> 建立监听线程1");

        // Start the thread to listen on a BluetoothServerSocket
        if (mSecureAcceptThread == null) {
            KLog.d("start --> 建立监听线程2");

            mSecureAcceptThread = new AcceptThread();
            mSecureAcceptThread.start();
        }
    }

    /**
     * Stop all threads
     * <p>
     * 取消并置空本类中所有的线程（共3个）
     */
    public void stop() {

        setState(STATE_NONE);

        if (mConnectThread != null) {
            mConnectThread.cancel();
            mConnectThread = null;
        }

        KLog.d("mConnectThread != null --> " + (mConnectThread != null));
        if (mConnectedThread != null) {
            mConnectedThread.cancel();
            mConnectedThread = null;

            KLog.d("stop --> mConnectThread = null");
        }

        if (mSecureAcceptThread != null) {
            mSecureAcceptThread.cancel();
            mSecureAcceptThread = null;
        }

//        setState(STATE_NONE);
    }

    /**
     * Start the ConnectThread to initiate a connection to a remote device.
     */
    public synchronized void connect(BluetoothDevice device) {
        // Cancel any thread attempting to make a connection
        //if (mState == STATE_CONNECTING) {
        if (mConnectThread != null) {
            mConnectThread.cancel();
            mConnectThread = null;
        }
        //}

        // Cancel any thread currently running a connection
        if (mConnectedThread != null) {
            mConnectedThread.cancel(); //注意如果执行到这里，ConnectedThread 中的 connect() 会产生异常, 并且会 setState(STATE_NONE), 这个和下面的 setState(STATE_CONNECTING); 无法判断谁先谁后执行，因为是不同的线程
            mConnectedThread = null;
        }

        // Start the thread to connect with the given device
        // ConnectThread 连接操作的线程是在这个 connect() 方法的末尾才启动的，所以可以在这里设置 STATE_CONNECTING 状态
        mConnectThread = new ConnectThread(device);
        mConnectThread.start();
        setState(STATE_CONNECTING);
    }

    /**
     * Start the ConnectedThread to begin managing a Bluetooth connection
     */
    public synchronized void connected(BluetoothSocket socket, BluetoothDevice device) {

        // 注意：下面的3个线程若不为null,则在 cancel() 是都会产生异常，都会调用 setState(),因此和本方法中的 setState() 相比，无法判断谁先谁后执行

        // Cancel the thread that completed the connection
        if (mConnectThread != null) {
            //mConnectThread.cancel();  // 连接成功后，不应该关闭新的 BluetoothSocket，否则 读写数据的 Stream 也会关闭
            mConnectThread = null;
        }

        // Cancel any thread currently running a connection
        if (mConnectedThread != null) {
            mConnectedThread.cancel(); // 连接成功后，关闭旧的 BluetoothSocket。释放资源
            mConnectedThread = null;
        }

        // Cancel the accept thread because we only want to connect to one device
        if (mSecureAcceptThread != null) {
            mSecureAcceptThread.cancel();
            mSecureAcceptThread = null;
        }

        // 先设置状态，因为 ConnectedThread (读取数据线程)有用到状态判断
        setState(STATE_CONNECTED, device.getName()); // 所属的 connected() 方法本来就是在连接成功后由 ConnectThread 调用的。

        // Start the thread to manage the conncetion and perform transmissions
        mConnectedThread = new ConnectedThread(socket);
        mConnectedThread.start();
    }


    /**
     * Indicate that the connection attempt failed and notify the UI Activity.
     */
    private void sendConnectionFailed() {
        // Send a failure message back to the Activity
        Message msg = mHandler.obtainMessage(Constants.MESSAGE_TOAST);
        Bundle bundle = new Bundle();
        bundle.putString(Constants.TOAST, "Unable to connect device");
        msg.setData(bundle);
        mHandler.sendMessage(msg);

        KLog.d("sendConnectionFailed");

//        // Start the service over to restart listening mode
//        BluetoothChatService.this.start();
    }

    /**
     * Indicate that the connection was lost and notify the UI Activity.
     */
    private void sendConnectionLost() {
        // Send a failure message back to the Activity
        Message msg = mHandler.obtainMessage(Constants.MESSAGE_TOAST);
        Bundle bundle = new Bundle();
        bundle.putString(Constants.TOAST, "Device connection was lost");
        msg.setData(bundle);
        mHandler.sendMessage(msg);

//        // Start the service over to restart listeninng mode
//        BluetoothChatService.this.start();
    }

    /**
     * Write to the ConnectedThread in an unsynchronized manner
     */
    public void write(byte[] out) {
        // Create temporary object
        ConnectedThread r;
        // Synchronized a copy of the ConnectedThread
        synchronized (this) {
            if (mState != STATE_CONNECTED) return;
            r = mConnectedThread;
        }

        // Perform the write unsynchronized
        r.write(out);
    }


    /**
     * This thread runs while listening for incoming connections. It behaves
     * like a server-side client. It runs until a connection is accepted
     * (or until cancelled).
     */
    private class AcceptThread extends Thread {
        // The local server socket
        private final BluetoothServerSocket mmServerSocket;

        private AcceptThread() {
            BluetoothServerSocket tmp = null;

            // Create a new listening server socket
            try {
                tmp = mBtAdapter.listenUsingRfcommWithServiceRecord(NAME_SECURE, MY_UUID_SECURE);
            } catch (IOException e) {
                e.printStackTrace();
            }
            mmServerSocket = tmp;
        }

        @Override
        public void run() {

            BluetoothSocket socket = null;

            //Listen to the server socket if we're not connected
            while (mState != STATE_CONNECTED) {
                try {
                    // This is a blocking call and will only return on a
                    // successful connection or an exception.(aborted 、timeout)
                    socket = mmServerSocket.accept();//调用close() 时，这里会产生中断异常,并且会释放所有相关的的资源，但是不会关闭接收到的 BluetoothSocket
                } catch (IOException e) {
                    e.printStackTrace();
                    KLog.d("mmServerSocket.accept() --> exception");
                    break;
                }

                // if a connection was accepted
                // 此时需要判断 socket 和当前的状态是否相匹配，否则会出现状态紊乱
                if (socket != null) {
                    synchronized (BluetoothChatService.this) {
                        KLog.d("mState:" + mState);
                        switch (mState) {
                            case STATE_LISTEN:
                            case STATE_CONNECTING: // 刚开始不能理解这个状态为什么也是属于正常状态。后来仔细想明白了：若当前设备A正在发起请求连接B设备，但是此时还正处于尝试连接中，这时C设备正在连接A设备并且先连接成功，这时A设备就处于这个状态，B设备也会连接失败（因为socket.close()）
                                // Situation normal. Start the connected thread.
                                connected(socket, socket.getRemoteDevice()); //这里会调用mmServerSocket.close();
                                break; // 跳出循环
                            case STATE_NONE:
                            case STATE_CONNECTED:
                                // Either not ready or already connected. Terminate new socket.要么没有准备好，要么已经连接。终止新套接字。
                                try {
                                    socket.close();
                                } catch (IOException e) {
                                    e.printStackTrace();
                                }
                                break; // 跳出循环
                        }
                    }
                }
            }
        }

        public void cancel() {
            try {
                mmServerSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }


    /**
     * This thread runs while attempting to make an outgoing connection
     * with a device. It runs straight through; the connection either
     * succeeds or fails.
     */
    private class ConnectThread extends Thread {
        private final BluetoothDevice mmDevice;
        private final BluetoothSocket mmSocket;


        private ConnectThread(BluetoothDevice device) {
            mmDevice = device;
            BluetoothSocket tmp = null;

            try {
                tmp = device.createRfcommSocketToServiceRecord(MY_UUID_SECURE);
            } catch (IOException e) {
                e.printStackTrace();
            }
            mmSocket = tmp;
        }

        @Override
        public void run() {

            // Always cancel discovery because it will slow down a connection
            mBtAdapter.cancelDiscovery();

            // Make a connection to the BluetoothSocket
            try {
                // This is a blocking call and will only return on a successful connection or an exception
                mmSocket.connect(); // 注意：BluetoothSocket#close()方法会关闭Stream 和 释放所有相关的系统资源.所以在底部调用的connected()方法中不应该调用 mConnectThread.cancel(); 来关闭这个BluetoothSocket
            } catch (IOException e) {
                e.printStackTrace();
                // Close the socket
                try {
                    mmSocket.close();
                } catch (IOException e1) {
                    e1.printStackTrace();
                }
                sendConnectionFailed(); // 一旦connect() 出现了异常，就表示连接失败。而这个线程也就运行结束。若想要再次连接，必须要调用 start() 方法，重新创建此线程

                setState(STATE_NONE);

                // Start the service over to restart listening mode
                // BluetoothChatService.this.start(); // 当未连接蓝牙时，要保证这个类中的所有的线程都重新创建并启动
                return;
            }

            // Reset the connectionThread because we're done
            // 注意：不能关闭 socket， 否则就无法通讯了
            synchronized (BluetoothChatService.this) {
                mConnectThread = null;
            }

            // Start the connectedThread
            connected(mmSocket, mmDevice);
        }

        public void cancel() {
            try {
                mmSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * This thread runs during a connection with a remote device.
     * It handles all incoming and outgoing transmissions.
     */
    private class ConnectedThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final InputStream     mmInputStream;
        private final OutputStream    mmOutputStream;


        private ConnectedThread(BluetoothSocket socket) {
            this.mmSocket = socket;

            InputStream tmpIn = null;
            OutputStream tmpOut = null;

            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) {
                e.printStackTrace();
            }

            mmInputStream = tmpIn;
            mmOutputStream = tmpOut;
        }

        @Override
        public void run() {
            byte[] buffer = new byte[1024];
            int len;

            KLog.d("[ConnectedThread]before while, STATE_CONNECTED --> " + (mState == STATE_CONNECTED) + ", mState" + mState);

            // Keep listening to the InputStream while connected
            while (mState == STATE_CONNECTED) {
                KLog.d("[ConnectedThread]in while, mState == STATE_CONNECTED --> " + (mState == STATE_CONNECTED));
                try {
                    //Read from the InputStream
                    len = mmInputStream.read(buffer);

                    KLog.d("read:" + new String(buffer, 0, len).toString());

                    // Send the obtained bytes to the UI Activity
                    mHandler.obtainMessage(Constants.MESSAGE_READ, -1, -1, new String(buffer, 0, len)).sendToTarget(); //byte[] realData = Arrays.copyOf(buffer, len);
                } catch (IOException e) {// 一旦 read() 出现了异常，就表示连接已中断。而这个线程也就break掉，即运行结束。若想要读写数据，必须要重新连接
                    e.printStackTrace(); // 这里无法判断是程序退出而调用 stop() 方法产生的异常还是蓝牙关闭而产生的异常。若是调用 stop() 方法则不需要再次创建和开启 AcceptThread 线程了

                    // Close the socket
                    try {
                        mmSocket.close();
                    } catch (IOException e1) {
                        e1.printStackTrace();
                    }

                    synchronized (BluetoothChatService.this) {
                        mConnectedThread = null;
                    }

                    KLog.d("ConnectedThread --> IOException : " + e.toString() + " mState : " + mState);

                    if (mState != STATE_NONE) { // 若是调用 stop() 方法产生的异常，在 stop() 中会先设置 mState 为 STATE_NONE, 若是数据传输过程中不小心蓝牙突然断开，则此时 mState 仍然为STATE_CONNECTED
                        // Start the service over to restart listening mode
                        BluetoothChatService.this.start();// 当未连接蓝牙时，要监听线程（默认要作为服务端开启）
                    }

                    sendConnectionLost();

                    setState(STATE_NONE);


                    break; // 跳出循环
                }
            }
        }

        public void write(byte[] buffer) {
            try {
                mmOutputStream.write(buffer);
                KLog.d("[ConnectedThread]write, STATE_CONNECTED --> " + (mState == STATE_CONNECTED) + ", mState:" + mState);
                KLog.d("write:" + new String(buffer).toString());

                // Share the sent message back to the UI Activity
                mHandler.obtainMessage(Constants.MESSAGE_WRITE, -1, -1, new String(buffer)).sendToTarget();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        public void cancel() {
            try {
                mmSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
