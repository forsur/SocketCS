package Server;

import java.io.*;
import java.net.*;
import java.util.*;
import java.text.SimpleDateFormat;
import java.util.concurrent.ConcurrentHashMap;


// 待优化：使用线程池来多线程监听客户端连接请求

public class Server {
    private static ConcurrentHashMap<String, ClientHandler> clients = new ConcurrentHashMap<>();

    public static void main(String[] args) throws IOException {
        // 设置端口号
        final int port = 5799;

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("服务器已启动，监听端口：" + port);

            // 持续等待客户端连接
            while (true) {
                // 接受客户端连接
                Socket clientSocket = serverSocket.accept();

                // 将端口号 和 ClientHandler引用 存入 ConcurrentHashMap
                String clientPort = String.valueOf(clientSocket.getPort());
                ClientHandler handler = new ClientHandler(clientSocket);
                clients.put(clientPort, handler);

                // 为连接的客户端新创建一个线程，传入 Runnable 任务
                Thread clientThread = new Thread(handler);
                clientThread.start();
                System.out.println("一个用户已连接，端口号：" + clientSocket.getPort());
            }
        } catch (RuntimeException e) {
            throw new RuntimeException(e);
        }
    }


    /**
     * 客户端处理类
     */
    private static class ClientHandler implements Runnable {
        private final Socket socket;
        private final DataInputStream input;
        private final DataOutputStream output;

        // 设置构造方法，传入客户端对象
        public ClientHandler(Socket socket) throws IOException {
            this.socket = socket;
            input = new DataInputStream(socket.getInputStream());
            output = new DataOutputStream(socket.getOutputStream());
        }

        public void sendMessage(byte[] data) {
            try {
                output.flush();
                output.write(data);
                output.flush();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        private byte[] assemblePacket(int type, byte[] data) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            baos.write(0x02); // 起始标志
            baos.write(0x01); // 标记请求 / 响应
            baos.write(type);
            baos.write(data.length >> 8);
            baos.write(data.length & 0xFF);
            try {
                baos.write(data);
            } catch (IOException e) {
                e.printStackTrace();
            }
            baos.write(0x03); // 结束标志
            return baos.toByteArray();
        }

        private void sendResponse(int type, byte[] data) {
            sendMessage(assemblePacket(type, data));
        }

        private byte[] readPacket() throws IOException {
            // 检查起始标志
            if (input.readByte() != 0x02) {
                return null;
            }
            // 读取请求
            int auth = input.readByte();
            // 读取 type 字段
            int type = input.readByte();
            // 读取 payload 的 length 字段
            int length = input.readShort();
            // 读取长度为 length 的 payload 字段
            byte[] data = new byte[length];
            input.readFully(data);
            // 检查结束标志
            if (input.readByte() != 0x03) {
                return null;
            }

            // 打包工作
            byte[] packet = new byte[1 + data.length]; // 包含类型
            packet[0] = (byte) type;
            System.arraycopy(data, 0, packet, 1, data.length);
            return packet;
        }

        @Override
        public void run() {
            try {
                sendMessage("欢迎连接到服务器！".getBytes());
                while (true) {
                    // 解析数据包
                    byte[] data = readPacket();
                    if (data == null) {
                        continue;
                    }
                    // 分离 type 和 payload
                    int type = data[0];
                    byte[] payload = Arrays.copyOfRange(data, 1, data.length);

                    // 处理请求
                    handleRequest(type, payload);
                }
            } catch (IOException e) {
                System.out.println("客户端断开连接：" + socket.getRemoteSocketAddress());
            } finally {
                try {
                    socket.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                clients.remove(this);
            }
        }

        /**
         * 处理客户端请求。
         *
         * @param type 请求类型
         * @param payload 请求数据
         */
        private void handleRequest(int type, byte[] payload) throws IOException {
            switch (type) {
                // 时间请求
                case 0x01:
                    String time = new SimpleDateFormat("HH:mm:ss").format(new Date());
                    sendResponse(0x01, time.getBytes());
                    break;
                // 名字请求
                case 0x02:
                    String serverName = "JavaServer";
                    sendResponse(0x02, serverName.getBytes());
                    break;
                // 客户端列表请求
                case 0x03:
                    StringBuilder clientList = new StringBuilder();
                    synchronized (clients) {
                        for (Map.Entry<String, ClientHandler> entry : clients.entrySet()) {
                            // 获取客户端口号 与 引用
                            String clientPort = entry.getKey();
                            ClientHandler handler = entry.getValue();
                            clientList.append(Integer.parseInt(clientPort)).append(": ")
                                    .append(handler.socket.getRemoteSocketAddress()).append("\n");
                        }
                    }
                    sendResponse(0x03, clientList.toString().getBytes());
                    break;
                // 消息转发
                case 0x04:
                    // 从 payload 中提取端口号（前 2 个字节，表示一个 short 类型的端口号）
                    int targetPort = ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
                    String port = String.valueOf(targetPort);
                    // 提取消息内容（从第 3 个字节开始）
                    String message = new String(payload, 2, payload.length - 2);

                    // 检查端口号是否有效
                    if (!clients.containsKey(port)) {
                        sendResponse(0x00, "目标客户端不存在".getBytes());
                    } else {
                        // 获取目标 ClientHandler
                        ClientHandler targetHandler = clients.get(port);

                        // 构造包含发送者端口号的消息
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        System.out.println(socket.getPort());
                        baos.write((socket.getPort() >> 8) & 0xFF); // 发送者端口高字节
                        baos.write(socket.getPort() & 0xFF);        // 发送者端口低字节
                        baos.write(message.getBytes());

                        // 发送消息到目标客户端
                        targetHandler.sendMessage(assemblePacket(0x04, baos.toByteArray()));

                        // 发送响应给当前客户端
                        sendResponse(0x04, baos.toByteArray());
                    }
                    break;
                default:
                    sendResponse(0x00, "未知请求类型".getBytes());
            }
        }
    }
}
