package Server;

import java.io.*;
import java.net.*;
import java.util.*;
import java.text.SimpleDateFormat;
import java.util.concurrent.ConcurrentHashMap;

public class Server {
    private static ConcurrentHashMap<String, ClientHandler> clients = new ConcurrentHashMap<>();
    private static int requestCount = 0;

    public static void main(String[] args) throws IOException {
        final int port = 5799;

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("服务器已启动，监听端口：" + port);

            while (true) {
                Socket clientSocket = serverSocket.accept();
                String clientPort = String.valueOf(clientSocket.getPort());
                ClientHandler handler = new ClientHandler(clientSocket);
                clients.put(clientPort, handler);

                // 打印客户端端口号
                System.out.println("客户端已连接，端口号：" + clientPort);

                Thread clientThread = new Thread(handler);
                clientThread.start();
            }
        } catch (RuntimeException e) {
            throw new RuntimeException(e);
        }
    }

    private static class ClientHandler implements Runnable {
        private final Socket socket;
        private final DataInputStream input;
        private final DataOutputStream output;

        public ClientHandler(Socket socket) throws IOException {
            this.socket = socket;
            input = new DataInputStream(socket.getInputStream());
            output = new DataOutputStream(socket.getOutputStream());
        }

        public void sendMessage(byte[] data) {
            try {
                output.write(data);
                output.flush();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        private byte[] assemblePacket(int identity, int type, byte[] data) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            baos.write(0x02); // 起始标志
            baos.write(identity); // 身份字段
            baos.write(type); // 分类字段
            baos.write(data.length >> 8); // 长度标记高字节
            baos.write(data.length & 0xFF); // 长度标记低字节
            try {
                baos.write(data); // 正文内容
            } catch (IOException e) {
                e.printStackTrace();
            }
            baos.write(0x03); // 结束标志
            return baos.toByteArray();
        }

        private void sendResponse(int identity, int type, byte[] data) {
            sendMessage(assemblePacket(identity, type, data));
        }

        private byte[] readPacket() throws IOException {
            if (input.readByte() != 0x02) {
                return null;
            }
            int identity = input.readByte();
            int type = input.readByte();
            int length = input.readShort();
            byte[] data = new byte[length];
            input.readFully(data);
            if (input.readByte() != 0x03) {
                return null;
            }
            byte[] packet = new byte[3 + data.length];
            packet[0] = (byte) identity;
            packet[1] = (byte) type;
            System.arraycopy(data, 0, packet, 2, data.length);
            return packet;
        }

        @Override
        public void run() {
            try {
                sendMessage("欢迎连接到服务器！".getBytes());
                while (true) {
                    byte[] data = readPacket();
                    if (data == null) {
                        continue;
                    }
                    int identity = data[0];
                    int type = data[1];
                    byte[] payload = Arrays.copyOfRange(data, 2, data.length);
                    handleRequest(identity, type, payload);
                }
            } catch (IOException e) {
                System.out.println("客户端断开连接：" + socket.getRemoteSocketAddress());
            } finally {
                try {
                    socket.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                clients.remove(String.valueOf(socket.getPort()));
            }
        }

        private void handleRequest(int identity, int type, byte[] payload) throws IOException {
            switch (type) {
                case 0x01:
                    synchronized (Server.class) {
                        requestCount++;
                        System.out.println("处理请求数量：" + requestCount);
                    }
                    String time = new SimpleDateFormat("HH:mm:ss").format(new Date());
                    sendResponse(identity, 0x01, time.getBytes());
                    break;
                case 0x02:
                    String serverName = "JavaServer";
                    sendResponse(identity, 0x02, serverName.getBytes());
                    break;
                case 0x03:
                    StringBuilder clientList = new StringBuilder();
                    synchronized (clients) {
                        for (Map.Entry<String, ClientHandler> entry : clients.entrySet()) {
                            String clientPort = entry.getKey();
                            ClientHandler handler = entry.getValue();
                            clientList.append(Integer.parseInt(clientPort)).append(": ")
                                    .append(handler.socket.getRemoteSocketAddress()).append("\n");
                        }
                    }
                    sendResponse(identity, 0x03, clientList.toString().getBytes());
                    break;
                case 0x04:
                    int targetPort = ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
                    String port = String.valueOf(targetPort);
                    String message = new String(payload, 2, payload.length - 2);
                    if (!clients.containsKey(port)) {
                        sendResponse(identity, 0x04, "目标客户端不存在".getBytes());
                    } else {
                        ClientHandler targetHandler = clients.get(port);
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        baos.write((socket.getPort() >> 8) & 0xFF);
                        baos.write(socket.getPort() & 0xFF);
                        baos.write(message.getBytes());
                        targetHandler.sendMessage(assemblePacket(identity, 0x04, baos.toByteArray()));
                        sendResponse(identity, 0x04, "消息发送成功".getBytes());
                    }
                    break;
                default:
                    sendResponse(identity, 0x00, "未知请求类型".getBytes());
            }
        }
    }
}