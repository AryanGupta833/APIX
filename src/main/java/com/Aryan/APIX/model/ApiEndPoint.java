package com.Aryan.APIX.model;


import jakarta.websocket.server.ServerEndpoint;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.GeneratedColumn;

@Getter
@Setter
public class ApiEndPoint {

    private String method;
    private String path;
    private String summary;
}
