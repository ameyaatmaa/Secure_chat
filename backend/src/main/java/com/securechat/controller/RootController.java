package com.securechat.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
public class RootController implements ErrorController {

    @GetMapping("/")
    public String root(HttpServletRequest request) {
        return "forward:/index.html";
    }

    @RequestMapping("/error")
    public String handleError(HttpServletRequest request) {
        // Redirect all errors to the SPA frontend
        return "forward:/index.html";
    }
}
