package com.hanjari.testsupport;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 회귀 테스트용 응답 없는 서버. TCP 연결은 받지만 아무 바이트도 보내지 않는다.
 * MySQL 클라이언트는 서버 handshake를 기다리며 멈춘다(자격 증명은 전송되지 않는다).
 * {@link #close()}하면 받은 소켓을 모두 닫아 그 위에서 멈춘 클라이언트 스레드를 풀어 준다.
 */
public final class UnresponsiveServer implements AutoCloseable {

    private final ServerSocket serverSocket;
    private final List<Socket> accepted = new CopyOnWriteArrayList<>();
    private final Thread acceptor;

    private UnresponsiveServer(ServerSocket serverSocket) {
        this.serverSocket = serverSocket;
        this.acceptor = new Thread(this::acceptLoop, "hj-unresponsive-server");
        this.acceptor.setDaemon(true);
    }

    public static UnresponsiveServer start() throws IOException {
        UnresponsiveServer server = new UnresponsiveServer(new ServerSocket(0, 50, InetAddress.getLoopbackAddress()));
        server.acceptor.start();
        return server;
    }

    /** 이 서버를 가리키는 JDBC URL. 스키마 이름만 전용 스키마로 둔다. */
    public String jdbcUrl() {
        return "jdbc:mysql://127.0.0.1:" + serverSocket.getLocalPort() + "/" + TestDatabaseGuard.TEST_SCHEMA;
    }

    public int acceptedConnections() {
        return accepted.size();
    }

    private void acceptLoop() {
        while (!serverSocket.isClosed()) {
            try {
                accepted.add(serverSocket.accept());
            } catch (IOException e) {
                return;
            }
        }
    }

    @Override
    public void close() throws IOException {
        serverSocket.close();
        for (Socket socket : accepted) {
            socket.close();
        }
    }
}
