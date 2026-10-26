package cn.example.collector;

import java.awt.Desktop;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(2)
public class LocalPageLauncher implements ApplicationRunner {
    private final int port;

    public LocalPageLauncher(@Value("${server.port:8080}") int port) {
        this.port = port;
    }

    @Override
    public void run(ApplicationArguments args) {
        String address = "http://127.0.0.1:" + port;
        System.out.println("课程作业助手页面：" + address);
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(address));
            }
        } catch (Exception e) {
            System.out.println("未能自动打开页面，请将上面的本机地址复制到浏览器。");
        }
    }
}
