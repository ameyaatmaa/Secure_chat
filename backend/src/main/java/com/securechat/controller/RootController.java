package com.securechat.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.Map;

@Controller
public class RootController implements ErrorController {

    @GetMapping("/")
    public String root() {
        return "forward:/index.html";
    }

    @RequestMapping("/error")
    public ResponseEntity<?> handleError(HttpServletRequest request) {
        Integer status = (Integer) request.getAttribute("jakarta.servlet.error.status_code");
        if (status == null) status = 500;
        return ResponseEntity.status(HttpStatus.valueOf(status))
                .body(Map.of("error", "Error " + status));
    }
}
