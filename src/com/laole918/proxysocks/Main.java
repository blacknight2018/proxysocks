package com.laole918.proxysocks;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;

public class Main {

    public static void main(String[] args) {
        // write your code here
        try {
            int port = 1989;
            ServerSocket serverSocket = new ServerSocket(port);
            System.out.println(String.format("start proxy server，port at:%d", port));
            while (true) {
                Socket socket = serverSocket.accept();
                System.out.println("receive proxy request");
                proxy(socket);
                System.out.println("end proxy");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void proxy(Socket socket) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
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
                            Socket remote = new Socket(remote_address, remote_port);
                            System.out.println(String.format("tcp {name:%s,ip:%s,port:%d}", buffer1[3] == 0x03 ? remote_address.getHostName() : remote_address.getHostAddress(), remote_address.getHostAddress(), remote_port));
                            remote.setSoTimeout(1000 * 60 * 5);
                            socket_output.write(new byte[]{0x05, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00});
                            socket_output.flush();
                            InputStream remote_input = remote.getInputStream();
                            OutputStream remote_output = remote.getOutputStream();
                            CountDownLatch latch = new CountDownLatch(2);
                            System.out.println("start transfer tcp data");
                            transfer_tcp_stream(latch, socket_input, remote_output);
                            transfer_tcp_stream(latch, remote_input, socket_output);
                            latch.await();
                            System.out.println("end transfer tcp");
                            remote_input.close();
                            remote_output.close();
                            remote.close();
                        } else if (buffer1[1] == 0x03) {//o  UDP ASSOCIATE X'03'
                            DatagramSocket udp = new DatagramSocket();
                            udp.setSoTimeout(1000 * 60 * 5);
                            int udp_port = udp.getPort();
                            byte[] address_bytes = udp.getLocalAddress().getAddress();
                            byte[] temp = new byte[22];
                            temp[0] = 0x05;
                            temp[1] = temp[2] = 0x00;
                            temp[3] = address_bytes.length == 4 ? (byte) 0x01 : 0x04;
                            int length = 4;
                            System.arraycopy(address_bytes, 0, temp, length += address_bytes.length, address_bytes.length);
                            byte[] port_bytes = port2bytes(udp_port);
                            System.arraycopy(port_bytes, 0, temp, length += 2, 2);
                            socket_output.write(temp, 0, length);
                            socket_output.flush();
                            CountDownLatch latch = new CountDownLatch(1);
                            System.out.println("start transfer udp data");
                            System.out.println(String.format("udp 1 {ip:%s,port:%d}", remote_address.getHostAddress(), remote_port));
                            transfer_udp(latch, udp, socket.getInetAddress(), remote_port);
                            System.out.println("end transfer udp");
                            latch.await();
                            udp.close();
                        }
                    }
                    socket_input.close();
                    socket_output.close();
                    socket.close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }).start();
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

    private static void transfer_tcp_stream(CountDownLatch latch, InputStream input, OutputStream output) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] bytes = new byte[1024 * 8];
                int len;
                try {
                    while ((len = input.read(bytes)) > 0) {
                        output.write(bytes, 0, len);
                        output.flush();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
                latch.countDown();
            }
        }).start();
    }

    private static void transfer_udp(CountDownLatch latch, DatagramSocket socket, InetAddress client_address, int client_port) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Map<String, List<Object>> address_cache = new HashMap<>();
                    boolean running = true;
                    while (running) {
                        int BUFF_SIZE = 64 * 1024;
                        byte[] buffer = new byte[BUFF_SIZE];
                        DatagramPacket receive_packet = new DatagramPacket(buffer, BUFF_SIZE);
                        socket.receive(receive_packet);
                        if (receive_packet.getOffset() > 0) {
                            running = false;
                            continue;
                        }
                        String receive_host_address = receive_packet.getAddress().getHostAddress();
                        if (receive_host_address.equals(client_address.getHostAddress())) {
                            if (buffer[2] == 0x00) {
                                InetAddress remote_address = getInetAddressByBytes(buffer);
                                address_cache.put(remote_address.getHostAddress(), Arrays.asList(buffer[3], remote_address));
                                int offset = 4;
                                if (buffer[3] == 0x01) {
                                    offset += 4;
                                } else if (buffer[3] == 0x03) {
                                    offset += buffer[4] + 1;
                                } else if (buffer[3] == 0x04) {
                                    offset += 16;
                                }
                                int remote_port = bytes2port(buffer, offset += 2);
                                DatagramPacket send_packet = new DatagramPacket(buffer, offset, receive_packet.getOffset() - offset, remote_address, remote_port);
                                System.out.println(String.format("udp 2 {name:%s,ip:%s,port:%d}", buffer[3] == 0x03 ? remote_address.getHostName() : remote_address.getHostAddress(), remote_address.getHostAddress(), remote_port));
                                socket.send(send_packet);
                            }
                        } else {
                            /*
                                    +----+------+------+----------+----------+----------+
                                    |RSV | FRAG | ATYP | DST.ADDR | DST.PORT |   DATA   |
                                    +----+------+------+----------+----------+----------+
                                    | 2  |  1   |  1   | Variable |    2     | Variable |
                                    +----+------+------+----------+----------+----------+
                             */
                            if (address_cache.containsKey(receive_host_address)) {
                                byte type = (byte) address_cache.get(receive_host_address).get(0);
                                InetAddress receive_address = (InetAddress) address_cache.get(receive_host_address).get(1);
                                int receive_port = receive_packet.getPort();
                                byte[] temp = new byte[BUFF_SIZE];
                                temp[0] = temp[1] = temp[2] = 0x00;
                                temp[3] = type;
                                int offset = 4;
                                if (type == 0x01) {
                                    System.arraycopy(receive_address.getAddress(), 0, temp, offset += 4, 4);
                                } else if (type == 0x03) {
                                    byte[] host_name_bytes = receive_address.getHostName().getBytes();
                                    temp[4] = (byte) host_name_bytes.length;
                                    offset++;
                                    System.arraycopy(host_name_bytes, 0, temp, offset += temp[4], temp[4]);
                                } else if (type == 0x04) {
                                    System.arraycopy(receive_address.getAddress(), 0, temp, offset += 16, 16);
                                }
                                byte[] port_bytes = port2bytes(receive_port);
                                System.arraycopy(port_bytes, 0, temp, offset += 2, 2);
                                System.arraycopy(receive_packet.getData(), 0, temp, offset, receive_packet.getOffset());
                                DatagramPacket send_packet = new DatagramPacket(temp, 0, offset + receive_packet.getOffset(), client_address, client_port);
                                System.out.println(String.format("udp 3 {ip:%s,port:%d}", client_address.getHostAddress(), client_port));
                                socket.send(send_packet);
                            }
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
                latch.countDown();
            }
        }).start();
    }
}
