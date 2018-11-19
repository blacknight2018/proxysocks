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
        System.out.println(System.currentTimeMillis());
        SocketChannel remoteSocketChannel = (SocketChannel) key.channel();
        if (remoteSocketChannel.isConnectionPending()) {
            remoteSocketChannel.finishConnect();
            key.interestOps(key.interestOps() | SelectionKey.OP_READ);
            Object[] remote_objects = (Object[]) key.attachment();
            remote_objects[1] = true;

            SelectionKey selectionKey = (SelectionKey) remote_objects[3];
            Object[] objects = (Object[]) selectionKey.attachment();
            objects[1] = true;
            ByteBuffer byteBuffer = (ByteBuffer) objects[2];
            byteBuffer.clear();
            byteBuffer.put(new byte[]{0x05, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00});
            byteBuffer.flip();
            SocketChannel socketChannel = (SocketChannel) selectionKey.channel();
            socketChannel.write(byteBuffer);
            selectionKey.interestOps(SelectionKey.OP_READ);
            selector.wakeup();
        }
    }

    private static void read(SelectionKey key) throws IOException {
        key.interestOps(key.interestOps() & ~SelectionKey.OP_READ);
        sCachedThreadPool.submit(new Runnable() {
            @Override
            public void run() {
                workInThreadPool(key);
            }
        });
    }

    private static void workInThreadPool(SelectionKey key) {
        try {
            Object[] objects = (Object[]) key.attachment();
            boolean is_remote = (boolean) objects[0];
            boolean is_hand_shake = (boolean) objects[1];
            if (!is_remote && !is_hand_shake) {
                decode_socks5(key, objects);
            } else {
                transfer_data(key, objects);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    private static void transfer_data(SelectionKey key, Object[] objects) throws IOException {
        ByteBuffer byteBuffer = (ByteBuffer) objects[2];
        byteBuffer.clear();
        SocketChannel socketChannel = (SocketChannel) key.channel();
        int length = socketChannel.read(byteBuffer);
        if (length > 0) {
//            System.out.println(String.format("transfer length:%d", length));
            byteBuffer.flip();
            SelectionKey otherSelectionKey = (SelectionKey) objects[3];
            SocketChannel otherSocketChannel = (SocketChannel) otherSelectionKey.channel();
            do {
                otherSocketChannel.write(byteBuffer);
            } while (byteBuffer.hasRemaining());
        }
        key.interestOps(key.interestOps() | SelectionKey.OP_READ);
        key.selector().wakeup();
    }

    private static void decode_socks5(SelectionKey key, Object[] objects) throws IOException {
        Selector selector = key.selector();
        SocketChannel socketChannel = (SocketChannel) key.channel();
        int step = (int) objects[4];
        ByteBuffer byteBuffer = (ByteBuffer) objects[2];
        byteBuffer.clear();
        int length = socketChannel.read(byteBuffer);
        if (length <= 0) {
            key.interestOps(key.interestOps() | SelectionKey.OP_READ);
            selector.wakeup();
            return;
        }
        byteBuffer.flip();
        byte[] buffer = new byte[length];
        byteBuffer.get(buffer, 0, length);
        System.out.println(String.format("buffer0:%s", Arrays.toString(Arrays.copyOfRange(buffer, 0, length))));
        if (step == 0) {
            if (buffer[0] == 0x05) {
                byteBuffer.clear();
                byteBuffer.put(new byte[]{0x05, 0x00});
                byteBuffer.flip();
                socketChannel.write(byteBuffer);
                objects[4] = 1;
                key.interestOps(key.interestOps() | SelectionKey.OP_READ);
                selector.wakeup();
            }
        } else if (step == 1) {
            if (buffer[0] == 0x05) {
                InetAddress remote_address = getInetAddressByBytes(buffer);
                int remote_port = bytes2port(buffer, length - 2);
                if (buffer[1] == 0x01) {//o  CONNECT X'01'
                    System.out.println(String.format("tcp {name:%s,ip:%s,port:%d}", buffer[3] == 0x03 ? remote_address.getHostName() : remote_address.getHostAddress(), remote_address.getHostAddress(), remote_port));
                    SocketChannel remoteSocketChannel = SocketChannel.open();
                    remoteSocketChannel.configureBlocking(false);
                    System.out.println(System.currentTimeMillis());
                    remoteSocketChannel.connect(new InetSocketAddress(remote_address, remote_port));
                    remoteSocketChannel.socket().setReceiveBufferSize(1 * 1024);// 设置接收缓存
                    remoteSocketChannel.socket().setSendBufferSize(1 * 1024);// 设置发送缓存
                    remoteSocketChannel.socket().setSoTimeout(0);
                    remoteSocketChannel.socket().setTcpNoDelay(true);
                    remoteSocketChannel.socket().setKeepAlive(true);
                    SelectionKey remoteSelectionKey = remoteSocketChannel.register(selector, SelectionKey.OP_CONNECT);
                    Object[] remote_objects = new Object[4];
                    remote_objects[0] = true;
                    remote_objects[1] = false;
                    remote_objects[2] = ByteBuffer.allocateDirect(1024 * 10);
                    remote_objects[3] = key;
                    remoteSelectionKey.attach(remote_objects);

                    objects[3] = remoteSelectionKey;
                    objects[4] = 2;
                    selector.wakeup();
                    System.out.println(System.currentTimeMillis());
                }
            }
        }
    }

    private static ExecutorService sCachedThreadPool = Executors.newCachedThreadPool();

    private static void accept(Selector selector, SelectionKey key) throws IOException {
        ServerSocketChannel serverSocketChannel = (ServerSocketChannel) key.channel();
        SocketChannel socketChannel = serverSocketChannel.accept();
        socketChannel.configureBlocking(false);
        socketChannel.socket().setReceiveBufferSize(1 * 1024);// 设置接收缓存
        socketChannel.socket().setSendBufferSize(1 * 1024);// 设置发送缓存
        socketChannel.socket().setSoTimeout(0);
        socketChannel.socket().setTcpNoDelay(true);
        socketChannel.socket().setKeepAlive(true);
        SelectionKey selectionKey = socketChannel.register(selector, SelectionKey.OP_READ);
        Object[] objects = new Object[5];
        objects[0] = false;
        objects[1] = false;
        objects[2] = ByteBuffer.allocateDirect(1024 * 10);// 创建读取缓冲区
        objects[4] = 0;
        selectionKey.attach(objects);
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
