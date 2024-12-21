package Client;

import java.io.*;
import java.net.*;
import java.util.Scanner;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class Client {
    private Socket socket;
    private DataInputStream input;
    private DataOutputStream output;
    private boolean isConnected = false;
    private BlockingQueue<byte[]> messageQueue = new LinkedBlockingQueue<>();

    public static void main(String[] args) throws InterruptedException {
        Client client = new Client();
        client.start();
    }

    private void connect(String ip, int port) throws IOException {
        socket = new Socket(ip, port);
        input = new DataInputStream(socket.getInputStream());
        output = new DataOutputStream(socket.getOutputStream());
        isConnected = true;

        // 启动接收线程
        new Thread(new ReceiveHandler()).start();
        System.out.println("已连接服务器：" + socket.getRemoteSocketAddress());
    }

    private void disconnect() throws IOException {
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        isConnected = false;
        System.out.println("已断开连接！");
    }

    public void start() throws InterruptedException {
        Scanner scanner = new Scanner(System.in);
        new Thread(new MessageProcessor()).start();

        while (true) {
            Thread.currentThread().sleep(300);
            printMenu();
            int choice = scanner.nextInt();
            scanner.nextLine(); // 清空换行符
            try {
                switch (choice) {
                    case 1: // 连接服务器
                        if (!isConnected) {
                            System.out.print("请输入服务器IP：");
                            String ip = scanner.nextLine();
                            System.out.print("请输入服务器端口：");
                            int port = scanner.nextInt();
                            scanner.nextLine(); // 清空换行符
                            connect(ip, port);
                        } else {
                            System.out.println("已连接服务器，请勿重复连接！");
                        }
                        break;
                    case 2: // 断开连接
                        disconnect();
                        break;
                    case 3: // 获取时间
                        if (isConnected) sendRequest(0x01, new byte[0]);
                        break;
                    case 4: // 获取名字
                        if (isConnected) sendRequest(0x02, new byte[0]);
                        break;
                    case 5: // 获取客户端列表
                        if (isConnected) sendRequest(0x03, new byte[0]);
                        break;
                    case 6: // 发送消息
                        if (isConnected) {
                            System.out.print("请输入目标客户端编号：");
                            int target = scanner.nextInt();
                            scanner.nextLine(); // 清空换行符
                            System.out.print("请输入消息内容：");
                            String message = scanner.nextLine();
                            sendMessage(target, message);
                        }
                        break;
                    case 7: // 退出程序
                        if (isConnected) disconnect();
                        System.out.println("客户端已退出！");
                        System.exit(0);
                        break;
                    default:
                        System.out.println("无效选项，请重新输入！");
                }
            } catch (IOException e) {
                System.out.println("操作失败：" + e.getMessage());
            }
        }
    }

    private void printMenu() {
        System.out.println("请选择功能：");
        System.out.println("1. 连接服务器");
        System.out.println("2. 断开连接");
        System.out.println("3. 获取时间");
        System.out.println("4. 获取名字");
        System.out.println("5. 获取客户端列表");
        System.out.println("6. 发送消息");
        System.out.println("7. 退出程序");
        System.out.print("输入选项：");
    }

    private void sendRequest(int type, byte[] data) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write(0x02); // 起始标志
        baos.write(type);
        baos.write(data.length >> 8);
        baos.write(data.length & 0xFF);
        baos.write(data);
        baos.write(0x03); // 结束标志

        output.write(baos.toByteArray());
        output.flush();
    }

    private void sendMessage(int target, String message) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        // 将端口号拆分为 2 个字节（端口号是 16 位整数，范围为 0 到 65535）
        baos.write((target >> 8) & 0xFF); // 写入高字节
        baos.write(target & 0xFF);        // 写入低字节

        // 写入消息内容
        baos.write(message.getBytes());

        // 发送请求
        sendRequest(0x04, baos.toByteArray());
    }

    // 消息接收线程
    private class ReceiveHandler implements Runnable {
        @Override
        public void run() {
            try {
                while (isConnected) {
                    byte[] buffer = new byte[1024];
                    int length = input.read(buffer);
                    if (length > 0) {
                        // 取出起始和结尾字符
                        if (buffer[0] == 0x02 && buffer[length - 1] == 0x03) {
                            byte[] message = new byte[length - 2];
                            System.arraycopy(buffer, 1, message, 0, length - 2);
                            messageQueue.put(message); // Add message to queue
                        }
                    }
                }
            } catch (IOException | InterruptedException e) {
                if (isConnected) {
                    System.out.println("接收线程错误：" + e.getMessage());
                }
            }
        }
    }

    // 消息处理线程（处理 消息队列）
    private class MessageProcessor implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    byte[] message = messageQueue.take(); // 拿走头部元素
                    processMessage(message);
                } catch (InterruptedException e) {
                    System.out.println("消息处理线程错误：" + e.getMessage());
                }
            }
        }

        private void processMessage(byte[] message) {
            int type = message[0];
            byte[] payload = new byte[message.length - 1];
            // byte0 : type
            // byte1,2 : length
            System.arraycopy(message, 3, payload, 0, message.length - 3);
            // 根据服务器返回到消息队列的不同消息分别打印
            switch (type) {
                case 0x01:
                    System.out.println("时间：" + "\n" + new String(payload).trim());
                    break;
                case 0x02:
                    System.out.println("名字：" + "\n" + new String(payload).trim());
                    break;
                case 0x03:
                    System.out.println("客户端列表：" + "\n" + new String(payload).trim());
                    break;
                case 0x04:
                    // 计算 客户端口号
                    int senderPort = ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
                    String messageContent = new String(payload, 2, payload.length - 2).trim();
                    // 判断自己是发送方还是接收方，分别输出
                    if (senderPort == socket.getLocalPort()) {
                        System.out.println("消息发送成功");
                    } else {
                        System.out.println("\n消息来自端口 " + senderPort + "：" + "\n" + messageContent);
                    }
                    break;
                default:
                    System.out.println("未知消息类型：" + type);
            }
        }
    }
}