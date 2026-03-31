package com.securechat.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // app.js and style.css: never cache so updates always reach the browser
        registry.addResourceHandler("/app.js", "/style.css")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(CacheControl.noStore());

        // index.html: also no-cache
        registry.addResourceHandler("/index.html")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(CacheControl.noStore());
    }
}
