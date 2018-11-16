package com.laole918.proxysocks;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Arrays;
import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Main_nio {

    /**
     * nio socks5 proxy
     *
     * @param args
     */
    public static void main(String[] args) {
        // write your code here
        try {
            int port = 1989;
            Selector selector = Selector.open();
            ServerSocketChannel serverChannel = ServerSocketChannel.open();
            serverChannel.configureBlocking(false);
            InetSocketAddress isa = new InetSocketAddress(port);
            serverChannel.socket().bind(isa);
            serverChannel.register(selector, SelectionKey.OP_ACCEPT);
            System.out.println(String.format("start proxy server，port at:%d", port));
            while (true) {
                try {
//                    int num = selector.select(100);
                    int num = selector.select();
                    if (num > 0) {
                        Iterator selectedKeys = selector.selectedKeys().iterator();
                        while (selectedKeys.hasNext()) {
                            SelectionKey key = (SelectionKey) selectedKeys.next();
                            selectedKeys.remove();
                            if (!key.isValid()) {
                                continue;
                            }
                            if (key.isAcceptable()) {
                                System.out.println("receive proxy request");
                                accept(selector, key);
                            }
                            if (key.isConnectable()) {
                                connect(selector, key);
                            }
                            if (key.isReadable()) {
                                read(key);
                            }
                            if (key.isWritable()) {
                                write(key);
                            }
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
//            System.out.println("end proxy");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void connect(Selector selector, SelectionKey key) throws IOException {
        SocketChannel socketChannel = (SocketChannel) key.channel();
        if (socketChannel.isConnectionPending()) {
            socketChannel.finishConnect();
        }
    }

    private static void read(SelectionKey key) throws IOException {
        sCachedThreadPool.submit(new Runnable() {
            @Override
            public void run() {
                readInThreadPool(key);
            }
        });
    }

    private static void write(SelectionKey key) {
        sCachedThreadPool.submit(new Runnable() {
            @Override
            public void run() {
                writeInThreadPool(key);
            }
        });
    }

    private static void writeInThreadPool(SelectionKey key) {
        try {
            SocketChannel socketChannel = (SocketChannel) key.channel();
            key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
            Object[] objects = (Object[]) key.attachment();
            boolean is_remote = (boolean) objects[0];
            ConcurrentLinkedQueue<ByteBuffer> bufferQueue = (ConcurrentLinkedQueue<ByteBuffer>) objects[1];
            SelectionKey selectionKey = (SelectionKey) objects[2];
            ByteBuffer byteBuffer = bufferQueue.poll();
            while (byteBuffer != null) {
                if(byteBuffer.hasRemaining()) {
                    socketChannel.write(byteBuffer);
                } else {
                    byteBuffer.clear();
                    byteBuffer = bufferQueue.poll();
                }
            }
            key.interestOps(SelectionKey.OP_READ);
            selectionKey.interestOps(SelectionKey.OP_READ);
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    private static void readInThreadPool(SelectionKey key) {
        try {
            SocketChannel socketChannel = (SocketChannel) key.channel();
            key.interestOps(key.interestOps() & ~SelectionKey.OP_READ);
            Object[] objects = (Object[]) key.attachment();
            boolean is_remote = (boolean) objects[0];
            SelectionKey selectionKey = (SelectionKey) objects[2];
            ConcurrentLinkedQueue<ByteBuffer> bufferQueue = (ConcurrentLinkedQueue<ByteBuffer>) objects[3];
            int length;
            do {
                ByteBuffer byteBuffer = ByteBuffer.allocateDirect(1024 * 10);// 创建读取缓冲区
                length = socketChannel.read(byteBuffer);
                byteBuffer.flip();
                bufferQueue.offer(byteBuffer);
            } while (length > 0);
            key.interestOps(SelectionKey.OP_WRITE);
            selectionKey.interestOps(SelectionKey.OP_WRITE);
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    private static ExecutorService sCachedThreadPool = Executors.newCachedThreadPool();

    private static void accept(Selector selector, SelectionKey key) {
        sCachedThreadPool.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    ServerSocketChannel serverSocketChannel = (ServerSocketChannel) key.channel();
//                    key.interestOps(key.interestOps() & ~ SelectionKey.OP_ACCEPT);
                    SocketChannel socketChannel = serverSocketChannel.accept();
//                    if(socketChannel == null) return;
//                    socketChannel.configureBlocking(false);
                    socketChannel.socket().setReceiveBufferSize(5 * 1024);// 设置接收缓存
                    socketChannel.socket().setSendBufferSize(64 * 1024);// 设置发送缓存
                    socketChannel.socket().setSoTimeout(0);
                    socketChannel.socket().setTcpNoDelay(true);
                    socketChannel.socket().setKeepAlive(true);

                    Socket socket = socketChannel.socket();
                    InputStream socket_input = socket.getInputStream();
                    OutputStream socket_output = socket.getOutputStream();
                    /*
                                        +----+----------+----------+
                                        |VER | NMETHODS | METHODS  |
                                        +----+----------+----------+
                                        | 1  |    1     | 1 to 255 |
                                        +----+----------+----------+
                     */
                    byte[] buffer0 = new byte[512];//最长257
                    int total0 = socket_input.read(buffer0);
                    System.out.println(String.format("buffer0:%s", Arrays.toString(Arrays.copyOfRange(buffer0, 0, total0))));
                    if (buffer0[0] == 0x05) {
                        socket_output.write(new byte[]{0x05, 0x00});// o  X'00' NO AUTHENTICATION REQUIRED
                        socket_output.flush();
                        /*
                                +----+-----+-------+------+----------+----------+
                                |VER | CMD |  RSV  | ATYP | DST.ADDR | DST.PORT |
                                +----+-----+-------+------+----------+----------+
                                | 1  |  1  | X'00' |  1   | Variable |    2     |
                                +----+-----+-------+------+----------+----------+
                         */
                        byte[] buffer1 = new byte[1024];// 最长260字节（域名总长度则不能超过253个字符）
                        int total1 = socket_input.read(buffer1);
                        System.out.println(String.format("buffer1:%s", Arrays.toString(Arrays.copyOfRange(buffer1, 0, total1))));
                        InetAddress remote_address = getInetAddressByBytes(buffer1);
                        int remote_port = bytes2port(buffer1, total1 - 2);
                        if (buffer1[1] == 0x01) {//o  CONNECT X'01'
                            SocketChannel remoteSocketChannel = SocketChannel.open();
                            boolean connected = remoteSocketChannel.connect(new InetSocketAddress(remote_address, remote_port));
                            remoteSocketChannel.configureBlocking(false);
                            remoteSocketChannel.socket().setReceiveBufferSize(5 * 1024);// 设置接收缓存
                            remoteSocketChannel.socket().setSendBufferSize(64 * 1024);// 设置发送缓存
                            remoteSocketChannel.socket().setSoTimeout(0);
                            remoteSocketChannel.socket().setTcpNoDelay(true);
                            remoteSocketChannel.socket().setKeepAlive(true);
                            SelectionKey remoteSelectionKey;
                            if(connected) {
                                remoteSelectionKey = remoteSocketChannel.register(selector, 0);
                            } else {
                                remoteSelectionKey = remoteSocketChannel.register(selector, SelectionKey.OP_CONNECT);
                            }
                            System.out.println(String.format("tcp {name:%s,ip:%s,port:%d}", buffer1[3] == 0x03 ? remote_address.getHostName() : remote_address.getHostAddress(), remote_address.getHostAddress(), remote_port));
                            socket_output.write(new byte[]{0x05, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00});
                            socket_output.flush();

                            socketChannel.configureBlocking(false);
                            SelectionKey selectionKey = socketChannel.register(selector, SelectionKey.OP_READ);

                            ConcurrentLinkedQueue<ByteBuffer> bufferQueue = new ConcurrentLinkedQueue<>();// 当前正在传输的缓存区队列
                            ConcurrentLinkedQueue<ByteBuffer> remoteBufferQueue = new ConcurrentLinkedQueue<>();// 当前正在传输的缓存区队列
//                            ByteBuffer byteBuffer = ByteBuffer.allocateDirect(1024 * 10);// 创建读取缓冲区
//                            ByteBuffer remoteByteBuffer = ByteBuffer.allocateDirect(1024 * 10);// 创建读取缓冲区
                            Object[] objects = new Object[]{false, bufferQueue, remoteSelectionKey, remoteBufferQueue};
                            selectionKey.attach(objects);
                            Object[] remote_objects = new Object[]{true, remoteBufferQueue, selectionKey, bufferQueue};
                            remoteSelectionKey.attach(remote_objects);
                        } else if (buffer1[1] == 0x03) {//o  UDP ASSOCIATE X'03'

                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    private static InetAddress getInetAddressByBytes(byte[] buffer) throws UnknownHostException {
        int length = 0;
        if (buffer[3] == 0x01) {//o  IP V4 address: X'01'
            length = 4;
        } else if (buffer[3] == 0x03) {//o  DOMAINNAME: X'03'
            length = buffer[4];
        } else if (buffer[3] == 0x04) {//o  IP V6 address: X'04'
            length = 16;
        }
        if (buffer[3] == 0x03) {
            return InetAddress.getByName(new String(buffer, 5, length));
        } else {
            byte[] ip_bytes = new byte[length];
            System.arraycopy(buffer, 4, ip_bytes, 0, length);
            return InetAddress.getByAddress(ip_bytes);
        }
    }

    private static int bytes2port(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) << 8 | 0xff & bytes[offset + 1];
    }

    private static byte[] port2bytes(int port) {
        return new byte[]{(byte) ((port >> 8) & 0xFF), (byte) (port & 0xff)};
    }
}
