package com.debatechecker.audio;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final AudioStreamHandler audioStreamHandler;

    public WebSocketConfig(AudioStreamHandler audioStreamHandler) {
        this.audioStreamHandler = audioStreamHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Chrome extensions connect with Origin "chrome-extension://<id>".
        // Allow any extension for local dev; pin your extension ID before deploying.
        registry.addHandler(audioStreamHandler, "/audio")
                .setAllowedOriginPatterns("chrome-extension://*");
    }

    @Bean
    public ServletServerContainerFactoryBean webSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxBinaryMessageBufferSize(64 * 1024); // chunks are ~3.2 KB; leave headroom
        container.setMaxTextMessageBufferSize(16 * 1024);
        container.setMaxSessionIdleTimeout(5 * 60 * 1000L);
        return container;
    }
}
