# Geo-Locked Steganographic Secure Chat — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build a full-stack secure chat application where encrypted messages are hidden in images and can only be decrypted when the receiver is within 50 meters of a sender-defined GPS location.

**Architecture:** Monolith Spring Boot backend (Java 21) with PostgreSQL in Docker, NGINX reverse proxy with TLS, and a vanilla HTML + Alpine.js frontend. Crypto is split: server handles RSA key storage and AES-GCM encryption; client derives RSA keys from password via PBKDF2 and performs final decryption.

**Tech Stack:** Java 21, Spring Boot 3.x, Spring Security, Spring Data JPA, BouncyCastle, PostgreSQL 15, Docker Compose, NGINX, Flyway, HTML + Alpine.js + forge.js + Leaflet.js, GitHub CLI

---

## Project Structure (final)

```
Secure_chat/
├── backend/
│   ├── src/main/java/com/securechat/
│   │   ├── config/         SecurityConfig, JwtConfig
│   │   ├── controller/     AuthController, MessageController, LocationController, UserController
│   │   ├── service/        EncryptionService, SteganographyService, GeoLocationService, MessageService
│   │   ├── repository/     UserRepository, MessageRepository
│   │   ├── model/          User, Message
│   │   ├── security/       JwtProvider, JwtFilter
│   │   └── util/           HaversineCalculator
│   ├── src/main/resources/
│   │   ├── application.yml
│   │   └── db/migration/   V1__init.sql
│   ├── src/test/java/com/securechat/
│   ├── Dockerfile
│   └── pom.xml
├── frontend/
│   ├── index.html
│   ├── app.js
│   └── style.css
├── nginx/
│   └── nginx.conf
├── docker-compose.yml
├── .env.example
└── docs/plans/
```

---

## Task 1: Project Scaffold — Spring Boot with Maven

**Files:**
- Create: `backend/pom.xml`
- Create: `backend/src/main/resources/application.yml`

**Step 1: Create pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.2.3</version>
    </parent>
    <groupId>com.securechat</groupId>
    <artifactId>secure-chat</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <name>secure-chat</name>

    <properties>
        <java.version>21</java.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-database-postgresql</artifactId>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-api</artifactId>
            <version>0.12.5</version>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-impl</artifactId>
            <version>0.12.5</version>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-jackson</artifactId>
            <version>0.12.5</version>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>org.bouncycastle</groupId>
            <artifactId>bcprov-jdk18on</artifactId>
            <version>1.77</version>
        </dependency>
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.security</groupId>
            <artifactId>spring-security-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>com.github.bucket4j</groupId>
            <artifactId>bucket4j-core</artifactId>
            <version>8.7.0</version>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <configuration>
                    <excludes>
                        <exclude>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                        </exclude>
                    </excludes>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

**Step 2: Create application.yml**

```yaml
spring:
  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/securechat}
    username: ${SPRING_DATASOURCE_USERNAME:chatuser}
    password: ${SPRING_DATASOURCE_PASSWORD:strongpassword}
    driver-class-name: org.postgresql.Driver
  jpa:
    hibernate:
      ddl-auto: validate
    show-sql: false
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
  flyway:
    enabled: true
    locations: classpath:db/migration
  servlet:
    multipart:
      max-file-size: 5MB
      max-request-size: 10MB

server:
  port: 8080

app:
  jwt:
    secret: ${JWT_SECRET:change-this-secret-in-production-min-32-chars}
    expiration-ms: 86400000  # 24 hours
  image:
    storage-path: ${IMAGE_STORAGE_PATH:./images}
  geo:
    default-radius-meters: 50
```

**Step 3: Create main application class**

Create `backend/src/main/java/com/securechat/SecureChatApplication.java`:
```java
package com.securechat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class SecureChatApplication {
    public static void main(String[] args) {
        SpringApplication.run(SecureChatApplication.class, args);
    }
}
```

**Step 4: Commit**
```bash
cd ~/Desktop/Secure_chat
git add backend/
git commit -m "feat: scaffold Spring Boot project with pom.xml and application.yml"
```

---

## Task 2: Database Schema with Flyway

**Files:**
- Create: `backend/src/main/resources/db/migration/V1__init.sql`

**Step 1: Create migration file**

```sql
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TABLE users (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username      VARCHAR(50) UNIQUE NOT NULL,
    password_hash VARCHAR(60) NOT NULL,
    public_key    TEXT NOT NULL,
    created_at    TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE messages (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sender_id        UUID NOT NULL REFERENCES users(id),
    receiver_id      UUID NOT NULL REFERENCES users(id),
    image_filename   VARCHAR(255) NOT NULL,
    encrypted_key    TEXT NOT NULL,
    sender_lat       DOUBLE PRECISION NOT NULL,
    sender_lon       DOUBLE PRECISION NOT NULL,
    radius_meters    INTEGER NOT NULL DEFAULT 50,
    created_at       TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_messages_receiver ON messages(receiver_id);
CREATE INDEX idx_messages_sender ON messages(sender_id);
```

**Step 2: Commit**
```bash
git add backend/src/main/resources/db/migration/
git commit -m "feat: add Flyway migration V1 - users and messages schema"
```

---

## Task 3: JPA Models

**Files:**
- Create: `backend/src/main/java/com/securechat/model/User.java`
- Create: `backend/src/main/java/com/securechat/model/Message.java`

**Step 1: Create User model**

```java
package com.securechat.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(unique = true, nullable = false, length = 50)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 60)
    private String passwordHash;

    @Column(name = "public_key", nullable = false, columnDefinition = "TEXT")
    private String publicKey;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }
}
```

**Step 2: Create Message model**

```java
package com.securechat.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "messages")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Message {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sender_id", nullable = false)
    private User sender;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "receiver_id", nullable = false)
    private User receiver;

    @Column(name = "image_filename", nullable = false)
    private String imageFilename;

    @Column(name = "encrypted_key", nullable = false, columnDefinition = "TEXT")
    private String encryptedKey;

    @Column(name = "sender_lat", nullable = false)
    private Double senderLat;

    @Column(name = "sender_lon", nullable = false)
    private Double senderLon;

    @Column(name = "radius_meters", nullable = false)
    private Integer radiusMeters;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }
}
```

**Step 3: Create repositories**

Create `backend/src/main/java/com/securechat/repository/UserRepository.java`:
```java
package com.securechat.repository;

import com.securechat.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByUsername(String username);
    boolean existsByUsername(String username);
}
```

Create `backend/src/main/java/com/securechat/repository/MessageRepository.java`:
```java
package com.securechat.repository;

import com.securechat.model.Message;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.UUID;

public interface MessageRepository extends JpaRepository<Message, UUID> {
    @Query("SELECT m FROM Message m JOIN FETCH m.sender WHERE m.receiver.id = :receiverId ORDER BY m.createdAt DESC")
    List<Message> findByReceiverIdOrderByCreatedAtDesc(UUID receiverId);
}
```

**Step 4: Commit**
```bash
git add backend/src/main/java/com/securechat/model/ backend/src/main/java/com/securechat/repository/
git commit -m "feat: add JPA models and repositories for User and Message"
```

---

## Task 4: Haversine Utility

**Files:**
- Create: `backend/src/main/java/com/securechat/util/HaversineCalculator.java`
- Create: `backend/src/test/java/com/securechat/util/HaversineCalculatorTest.java`

**Step 1: Write failing test**

```java
package com.securechat.util;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class HaversineCalculatorTest {

    @Test
    void samePoint_returnsZero() {
        double dist = HaversineCalculator.distanceMeters(12.9716, 77.5946, 12.9716, 77.5946);
        assertThat(dist).isEqualTo(0.0);
    }

    @Test
    void knownDistance_bengaluruToMysuru_isApprox140km() {
        double dist = HaversineCalculator.distanceMeters(12.9716, 77.5946, 12.2958, 76.6394);
        assertThat(dist).isBetween(138_000.0, 142_000.0);
    }

    @Test
    void within50Meters_returnsTrue() {
        // offset ~30m north
        double dist = HaversineCalculator.distanceMeters(12.9716, 77.5946, 12.97187, 77.5946);
        assertThat(dist).isLessThan(50.0);
    }

    @Test
    void beyond50Meters_returnsFalse() {
        // offset ~100m north
        double dist = HaversineCalculator.distanceMeters(12.9716, 77.5946, 12.97250, 77.5946);
        assertThat(dist).isGreaterThan(50.0);
    }
}
```

**Step 2: Run test to verify it fails**
```bash
cd ~/Desktop/Secure_chat/backend
mvn test -Dtest=HaversineCalculatorTest -pl . 2>&1 | tail -20
```
Expected: FAIL — `HaversineCalculator` does not exist.

**Step 3: Implement HaversineCalculator**

```java
package com.securechat.util;

public class HaversineCalculator {

    private static final double EARTH_RADIUS_METERS = 6_371_000.0;

    public static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_METERS * c;
    }
}
```

**Step 4: Run tests to verify pass**
```bash
mvn test -Dtest=HaversineCalculatorTest
```
Expected: 4 tests PASS.

**Step 5: Commit**
```bash
git add backend/src/main/java/com/securechat/util/ backend/src/test/
git commit -m "feat: add HaversineCalculator with tests"
```

---

## Task 5: JWT Provider + Filter

**Files:**
- Create: `backend/src/main/java/com/securechat/security/JwtProvider.java`
- Create: `backend/src/main/java/com/securechat/security/JwtFilter.java`
- Create: `backend/src/test/java/com/securechat/security/JwtProviderTest.java`

**Step 1: Write failing test**

```java
package com.securechat.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class JwtProviderTest {

    private JwtProvider jwtProvider;

    @BeforeEach
    void setUp() {
        jwtProvider = new JwtProvider("test-secret-key-min-32-characters-long", 3600000L);
    }

    @Test
    void generateAndValidate_roundTrip() {
        String token = jwtProvider.generateToken("alice");
        assertThat(jwtProvider.validateToken(token)).isTrue();
        assertThat(jwtProvider.getUsernameFromToken(token)).isEqualTo("alice");
    }

    @Test
    void tamperedToken_isInvalid() {
        String token = jwtProvider.generateToken("alice");
        String tampered = token.substring(0, token.length() - 5) + "XXXXX";
        assertThat(jwtProvider.validateToken(tampered)).isFalse();
    }

    @Test
    void expiredToken_isInvalid() {
        JwtProvider shortLived = new JwtProvider("test-secret-key-min-32-characters-long", 1L);
        String token = shortLived.generateToken("alice");
        try { Thread.sleep(10); } catch (InterruptedException ignored) {}
        assertThat(shortLived.validateToken(token)).isFalse();
    }
}
```

**Step 2: Run test to verify it fails**
```bash
mvn test -Dtest=JwtProviderTest
```
Expected: FAIL.

**Step 3: Implement JwtProvider**

```java
package com.securechat.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class JwtProvider {

    private final SecretKey key;
    private final long expirationMs;

    public JwtProvider(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.expiration-ms}") long expirationMs) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;
    }

    public String generateToken(String username) {
        return Jwts.builder()
                .subject(username)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expirationMs))
                .signWith(key)
                .compact();
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public String getUsernameFromToken(String token) {
        return Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload().getSubject();
    }
}
```

**Step 4: Implement JwtFilter**

```java
package com.securechat.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;

@Component
public class JwtFilter extends OncePerRequestFilter {

    private final JwtProvider jwtProvider;
    private final UserDetailsService userDetailsService;

    public JwtFilter(JwtProvider jwtProvider, UserDetailsService userDetailsService) {
        this.jwtProvider = jwtProvider;
        this.userDetailsService = userDetailsService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String token = extractTokenFromCookie(request);
        if (token != null && jwtProvider.validateToken(token)) {
            String username = jwtProvider.getUsernameFromToken(token);
            var userDetails = userDetailsService.loadUserByUsername(username);
            var auth = new UsernamePasswordAuthenticationToken(
                    userDetails, null, userDetails.getAuthorities());
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
        filterChain.doFilter(request, response);
    }

    private String extractTokenFromCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        return Arrays.stream(cookies)
                .filter(c -> "jwt".equals(c.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);
    }
}
```

**Step 5: Run tests**
```bash
mvn test -Dtest=JwtProviderTest
```
Expected: 3 tests PASS.

**Step 6: Commit**
```bash
git add backend/src/main/java/com/securechat/security/ backend/src/test/
git commit -m "feat: add JWT provider and filter with cookie-based token extraction"
```

---

## Task 6: Security Config + UserDetailsService

**Files:**
- Create: `backend/src/main/java/com/securechat/config/SecurityConfig.java`

**Step 1: Create SecurityConfig**

```java
package com.securechat.config;

import com.securechat.repository.UserRepository;
import com.securechat.security.JwtFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtFilter jwtFilter;
    private final UserRepository userRepository;

    public SecurityConfig(JwtFilter jwtFilter, UserRepository userRepository) {
        this.jwtFilter = jwtFilter;
        this.userRepository = userRepository;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login").permitAll()
                .requestMatchers("/", "/index.html", "/app.js", "/style.css").permitAll()
                .anyRequest().authenticated()
            )
            .headers(headers -> headers
                .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000))
                .frameOptions(frame -> frame.deny())
                .contentTypeOptions(ct -> {})
                .referrerPolicy(ref -> ref.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
            )
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public UserDetailsService userDetailsService() {
        return username -> userRepository.findByUsername(username)
                .map(user -> User.withUsername(user.getUsername())
                        .password(user.getPasswordHash())
                        .authorities("ROLE_USER")
                        .build())
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
```

**Step 2: Commit**
```bash
git add backend/src/main/java/com/securechat/config/
git commit -m "feat: add Spring Security config with JWT filter, BCrypt, stateless sessions"
```

---

## Task 7: Auth Controller + DTOs

**Files:**
- Create: `backend/src/main/java/com/securechat/controller/AuthController.java`
- Create: `backend/src/main/java/com/securechat/controller/dto/RegisterRequest.java`
- Create: `backend/src/main/java/com/securechat/controller/dto/LoginRequest.java`
- Create: `backend/src/test/java/com/securechat/controller/AuthControllerTest.java`

**Step 1: Create DTOs**

`RegisterRequest.java`:
```java
package com.securechat.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
    @NotBlank @Size(min = 3, max = 50) String username,
    @NotBlank @Size(min = 8) String password,
    @NotBlank String publicKey
) {}
```

`LoginRequest.java`:
```java
package com.securechat.controller.dto;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
    @NotBlank String username,
    @NotBlank String password
) {}
```

**Step 2: Create AuthController**

```java
package com.securechat.controller;

import com.securechat.controller.dto.LoginRequest;
import com.securechat.controller.dto.RegisterRequest;
import com.securechat.model.User;
import com.securechat.repository.UserRepository;
import com.securechat.security.JwtProvider;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authManager;
    private final JwtProvider jwtProvider;

    public AuthController(UserRepository userRepository,
                          PasswordEncoder passwordEncoder,
                          AuthenticationManager authManager,
                          JwtProvider jwtProvider) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authManager = authManager;
        this.jwtProvider = jwtProvider;
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest req) {
        if (userRepository.existsByUsername(req.username())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Username already taken"));
        }
        User user = User.builder()
                .username(req.username())
                .passwordHash(passwordEncoder.encode(req.password()))
                .publicKey(req.publicKey())
                .build();
        userRepository.save(user);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("message", "Registered successfully"));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest req, HttpServletResponse response) {
        authManager.authenticate(new UsernamePasswordAuthenticationToken(req.username(), req.password()));
        String token = jwtProvider.generateToken(req.username());
        Cookie cookie = new Cookie("jwt", token);
        cookie.setHttpOnly(true);
        cookie.setSecure(true);
        cookie.setPath("/");
        cookie.setMaxAge(86400);
        response.addCookie(cookie);
        return ResponseEntity.ok(Map.of("message", "Login successful", "username", req.username()));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletResponse response) {
        Cookie cookie = new Cookie("jwt", "");
        cookie.setHttpOnly(true);
        cookie.setSecure(true);
        cookie.setPath("/");
        cookie.setMaxAge(0);
        response.addCookie(cookie);
        return ResponseEntity.ok(Map.of("message", "Logged out"));
    }
}
```

**Step 3: Write integration test**

```java
package com.securechat.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securechat.controller.dto.RegisterRequest;
import com.securechat.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;

    @BeforeEach
    void cleanup() { userRepository.deleteAll(); }

    @Test
    void register_success() throws Exception {
        var req = new RegisterRequest("alice", "password123", "RSA_PUBLIC_KEY_PEM");
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());
    }

    @Test
    void register_duplicateUsername_returns409() throws Exception {
        var req = new RegisterRequest("alice", "password123", "RSA_PUBLIC_KEY_PEM");
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict());
    }
}
```

Add `application-test.yml` in `src/test/resources/`:
```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/securechat_test
    username: chatuser
    password: strongpassword
  flyway:
    enabled: true
```

**Step 4: Run tests**
```bash
mvn test -Dtest=AuthControllerTest
```

**Step 5: Commit**
```bash
git add backend/src/main/java/com/securechat/controller/ backend/src/test/
git commit -m "feat: add AuthController with register/login/logout endpoints"
```

---

## Task 8: Encryption Service (BouncyCastle)

**Files:**
- Create: `backend/src/main/java/com/securechat/service/EncryptionService.java`
- Create: `backend/src/test/java/com/securechat/service/EncryptionServiceTest.java`

**Step 1: Write failing tests**

```java
package com.securechat.service;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;

import static org.assertj.core.api.Assertions.*;

class EncryptionServiceTest {

    private final EncryptionService service = new EncryptionService();

    @BeforeAll
    static void registerBC() {
        Security.addProvider(new BouncyCastleProvider());
    }

    @Test
    void aesGcm_encryptDecrypt_roundTrip() throws Exception {
        String plaintext = "Hello, secure world!";
        byte[] key = service.generateAesKey();
        EncryptionService.AesResult result = service.aesEncrypt(plaintext.getBytes(), key);
        byte[] decrypted = service.aesDecrypt(result.ciphertext(), result.iv(), key);
        assertThat(new String(decrypted)).isEqualTo(plaintext);
    }

    @Test
    void aesGcm_tamperedCiphertext_throwsException() throws Exception {
        byte[] key = service.generateAesKey();
        EncryptionService.AesResult result = service.aesEncrypt("data".getBytes(), key);
        result.ciphertext()[0] ^= 0xFF; // tamper
        assertThatThrownBy(() -> service.aesDecrypt(result.ciphertext(), result.iv(), key))
                .isInstanceOf(Exception.class);
    }

    @Test
    void rsaEncryptDecrypt_roundTrip() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA", "BC");
        gen.initialize(2048);
        KeyPair pair = gen.generateKeyPair();
        byte[] aesKey = service.generateAesKey();
        byte[] encrypted = service.rsaEncrypt(aesKey, pair.getPublic());
        byte[] decrypted = service.rsaDecrypt(encrypted, pair.getPrivate());
        assertThat(decrypted).isEqualTo(aesKey);
    }
}
```

**Step 2: Run to verify failure**
```bash
mvn test -Dtest=EncryptionServiceTest
```

**Step 3: Implement EncryptionService**

```java
package com.securechat.service;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.*;

@Service
public class EncryptionService {

    private static final String AES_ALGO = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    public record AesResult(byte[] ciphertext, byte[] iv) {}

    public byte[] generateAesKey() throws NoSuchAlgorithmException {
        KeyGenerator gen = KeyGenerator.getInstance("AES");
        gen.init(256);
        return gen.generateKey().getEncoded();
    }

    public AesResult aesEncrypt(byte[] plaintext, byte[] key) throws Exception {
        byte[] iv = new byte[IV_BYTES];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance(AES_ALGO, "BC");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
        return new AesResult(cipher.doFinal(plaintext), iv);
    }

    public byte[] aesDecrypt(byte[] ciphertext, byte[] iv, byte[] key) throws Exception {
        Cipher cipher = Cipher.getInstance(AES_ALGO, "BC");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
        return cipher.doFinal(ciphertext);
    }

    public byte[] rsaEncrypt(byte[] data, PublicKey publicKey) throws Exception {
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding", "BC");
        cipher.init(Cipher.ENCRYPT_MODE, publicKey);
        return cipher.doFinal(data);
    }

    public byte[] rsaDecrypt(byte[] data, PrivateKey privateKey) throws Exception {
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding", "BC");
        cipher.init(Cipher.DECRYPT_MODE, privateKey);
        return cipher.doFinal(data);
    }
}
```

**Step 4: Run tests**
```bash
mvn test -Dtest=EncryptionServiceTest
```
Expected: 3 PASS.

**Step 5: Commit**
```bash
git add backend/src/main/java/com/securechat/service/EncryptionService.java backend/src/test/
git commit -m "feat: add EncryptionService with AES-256-GCM and RSA-OAEP via BouncyCastle"
```

---

## Task 9: Steganography Service (LSB)

**Files:**
- Create: `backend/src/main/java/com/securechat/service/SteganographyService.java`
- Create: `backend/src/test/java/com/securechat/service/SteganographyServiceTest.java`

**Step 1: Write failing tests**

```java
package com.securechat.service;

import org.junit.jupiter.api.Test;
import java.awt.image.BufferedImage;
import static org.assertj.core.api.Assertions.*;

class SteganographyServiceTest {

    private final SteganographyService service = new SteganographyService();

    @Test
    void embed_and_extract_roundTrip() throws Exception {
        BufferedImage image = new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB);
        byte[] payload = "Hello steganography test payload!".getBytes();
        BufferedImage encoded = service.embed(image, payload);
        byte[] extracted = service.extract(encoded);
        assertThat(extracted).isEqualTo(payload);
    }

    @Test
    void payloadTooLarge_throwsException() {
        // 10x10 image = 100 pixels * 3 channels = 300 bits = 37 bytes usable
        BufferedImage tinyImage = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        byte[] bigPayload = new byte[500];
        assertThatThrownBy(() -> service.embed(tinyImage, bigPayload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too large");
    }
}
```

**Step 2: Run to verify failure**
```bash
mvn test -Dtest=SteganographyServiceTest
```

**Step 3: Implement SteganographyService**

```java
package com.securechat.service;

import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;

@Service
public class SteganographyService {

    // Header: 4 bytes (int) for payload length
    private static final int HEADER_BYTES = 4;

    public BufferedImage embed(BufferedImage image, byte[] payload) {
        int totalBits = (HEADER_BYTES + payload.length) * 8;
        int availableBits = image.getWidth() * image.getHeight() * 3;
        if (totalBits > availableBits) {
            throw new IllegalArgumentException(
                "Payload too large for image. Payload: " + payload.length + " bytes, " +
                "image capacity: " + (availableBits / 8 - HEADER_BYTES) + " bytes"
            );
        }

        BufferedImage output = copyImage(image);
        byte[] data = prependLength(payload);
        int bitIndex = 0;

        outer:
        for (int y = 0; y < output.getHeight(); y++) {
            for (int x = 0; x < output.getWidth(); x++) {
                int rgb = output.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;

                if (bitIndex < data.length * 8) r = setBit(r, getBit(data, bitIndex++));
                if (bitIndex < data.length * 8) g = setBit(g, getBit(data, bitIndex++));
                if (bitIndex < data.length * 8) b = setBit(b, getBit(data, bitIndex++));

                output.setRGB(x, y, (r << 16) | (g << 8) | b);
                if (bitIndex >= data.length * 8) break outer;
            }
        }
        return output;
    }

    public byte[] extract(BufferedImage image) {
        // Read header (first 4*8 = 32 bits) to get payload length
        byte[] header = extractBits(image, 0, HEADER_BYTES * 8);
        int payloadLength = ByteBuffer.wrap(header).getInt();
        if (payloadLength <= 0 || payloadLength > image.getWidth() * image.getHeight() * 3 / 8) {
            throw new IllegalStateException("Invalid payload length in image header: " + payloadLength);
        }
        return extractBits(image, HEADER_BYTES * 8, payloadLength * 8);
    }

    private byte[] extractBits(BufferedImage image, int startBit, int numBits) {
        byte[] result = new byte[(numBits + 7) / 8];
        int bitIndex = 0;
        int totalBits = startBit + numBits;

        outer:
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                int[] channels = {(rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF};
                for (int c : channels) {
                    int globalBit = bitIndex * 3 / 3; // track absolute position
                    // simpler: just count bits sequentially
                    int absPos = y * image.getWidth() * 3 + x * 3 + (3 - channels.length);
                    // recalculate properly below
                    break;
                }
                // Proper sequential extraction
                int[] ch = {(rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF};
                for (int channel : ch) {
                    int absoluteBit = (y * image.getWidth() + x) * 3 + (channel == ch[0] ? 0 : channel == ch[1] ? 1 : 2);
                    // Just use bitIndex
                    if (bitIndex >= startBit && bitIndex < totalBits) {
                        int resultBit = bitIndex - startBit;
                        if ((channel & 1) == 1) {
                            result[resultBit / 8] |= (byte)(1 << (7 - (resultBit % 8)));
                        }
                    }
                    bitIndex++;
                    if (bitIndex >= totalBits) break outer;
                }
            }
        }
        return result;
    }

    private byte[] prependLength(byte[] payload) {
        byte[] result = new byte[HEADER_BYTES + payload.length];
        byte[] lengthBytes = ByteBuffer.allocate(4).putInt(payload.length).array();
        System.arraycopy(lengthBytes, 0, result, 0, HEADER_BYTES);
        System.arraycopy(payload, 0, result, HEADER_BYTES, payload.length);
        return result;
    }

    private int getBit(byte[] data, int index) {
        return (data[index / 8] >> (7 - (index % 8))) & 1;
    }

    private int setBit(int channel, int bit) {
        return (channel & 0xFE) | bit;
    }

    private BufferedImage copyImage(BufferedImage src) {
        BufferedImage copy = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        copy.getGraphics().drawImage(src, 0, 0, null);
        return copy;
    }
}
```

> **Note:** The extractBits method above has a logic issue in the loop. Rewrite it cleanly:

```java
private byte[] extractBits(BufferedImage image, int startBit, int numBits) {
    byte[] result = new byte[(numBits + 7) / 8];
    int currentBit = 0;
    int resultBit = 0;

    outer:
    for (int y = 0; y < image.getHeight(); y++) {
        for (int x = 0; x < image.getWidth(); x++) {
            int rgb = image.getRGB(x, y);
            int[] channels = {(rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF};
            for (int channel : channels) {
                if (currentBit >= startBit && resultBit < numBits) {
                    if ((channel & 1) == 1) {
                        result[resultBit / 8] |= (byte)(1 << (7 - (resultBit % 8)));
                    }
                    resultBit++;
                }
                currentBit++;
                if (resultBit >= numBits) break outer;
            }
        }
    }
    return result;
}
```

**Step 4: Run tests**
```bash
mvn test -Dtest=SteganographyServiceTest
```
Expected: 2 PASS.

**Step 5: Commit**
```bash
git add backend/src/main/java/com/securechat/service/SteganographyService.java backend/src/test/
git commit -m "feat: add SteganographyService with LSB embed/extract and length header"
```

---

## Task 10: GeoLocation Service

**Files:**
- Create: `backend/src/main/java/com/securechat/service/GeoLocationService.java`
- Create: `backend/src/test/java/com/securechat/service/GeoLocationServiceTest.java`

**Step 1: Write failing test**

```java
package com.securechat.service;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class GeoLocationServiceTest {

    private final GeoLocationService service = new GeoLocationService();

    @Test
    void coordinatesInRange_isValid() {
        assertThat(service.isCoarseValid(12.9716, 77.5946)).isTrue();
    }

    @Test
    void nullIsland_isInvalid() {
        assertThat(service.isCoarseValid(0.0, 0.0)).isFalse();
    }

    @Test
    void outOfRange_lat_isInvalid() {
        assertThat(service.isCoarseValid(91.0, 0.0)).isFalse();
        assertThat(service.isCoarseValid(-91.0, 0.0)).isFalse();
    }

    @Test
    void outOfRange_lon_isInvalid() {
        assertThat(service.isCoarseValid(0.0, 181.0)).isFalse();
    }
}
```

**Step 2: Implement GeoLocationService**

```java
package com.securechat.service;

import com.securechat.util.HaversineCalculator;
import org.springframework.stereotype.Service;

@Service
public class GeoLocationService {

    public boolean isCoarseValid(double lat, double lon) {
        if (lat == 0.0 && lon == 0.0) return false; // null island
        return lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180;
    }

    public boolean isWithinRadius(double senderLat, double senderLon,
                                   double receiverLat, double receiverLon,
                                   int radiusMeters) {
        if (!isCoarseValid(receiverLat, receiverLon)) return false;
        return HaversineCalculator.distanceMeters(senderLat, senderLon, receiverLat, receiverLon)
                <= radiusMeters;
    }
}
```

**Step 3: Run tests**
```bash
mvn test -Dtest=GeoLocationServiceTest
```
Expected: 4 PASS.

**Step 4: Commit**
```bash
git add backend/src/main/java/com/securechat/service/GeoLocationService.java backend/src/test/
git commit -m "feat: add GeoLocationService with coarse validation using Haversine"
```

---

## Task 11: Message Service + Controllers

**Files:**
- Create: `backend/src/main/java/com/securechat/service/MessageService.java`
- Create: `backend/src/main/java/com/securechat/controller/MessageController.java`
- Create: `backend/src/main/java/com/securechat/controller/LocationController.java`
- Create: `backend/src/main/java/com/securechat/controller/UserController.java`

**Step 1: Create MessageService**

```java
package com.securechat.service;

import com.securechat.model.Message;
import com.securechat.model.User;
import com.securechat.repository.MessageRepository;
import com.securechat.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

@Service
public class MessageService {

    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final SteganographyService steganographyService;
    private final GeoLocationService geoLocationService;
    private final Path imageStoragePath;

    public MessageService(MessageRepository messageRepository,
                          UserRepository userRepository,
                          SteganographyService steganographyService,
                          GeoLocationService geoLocationService,
                          @Value("${app.image.storage-path}") String storagePath) throws IOException {
        this.messageRepository = messageRepository;
        this.userRepository = userRepository;
        this.steganographyService = steganographyService;
        this.geoLocationService = geoLocationService;
        this.imageStoragePath = Paths.get(storagePath);
        Files.createDirectories(this.imageStoragePath);
    }

    public Message sendMessage(String senderUsername, String receiverUsername,
                                MultipartFile imageFile, String encryptedPayloadBase64,
                                String encryptedKey, double lat, double lon, int radius) throws Exception {
        validateImageType(imageFile);

        User sender = userRepository.findByUsername(senderUsername)
                .orElseThrow(() -> new IllegalArgumentException("Sender not found"));
        User receiver = userRepository.findByUsername(receiverUsername)
                .orElseThrow(() -> new IllegalArgumentException("Receiver not found"));

        if (!geoLocationService.isCoarseValid(lat, lon)) {
            throw new IllegalArgumentException("Invalid sender coordinates");
        }

        BufferedImage image = readImage(imageFile.getInputStream());
        byte[] payload = java.util.Base64.getDecoder().decode(encryptedPayloadBase64);
        BufferedImage encodedImage = steganographyService.embed(image, payload);

        String filename = UUID.randomUUID() + ".png";
        Path outputPath = imageStoragePath.resolve(filename);
        ImageIO.write(encodedImage, "PNG", outputPath.toFile());

        Message message = Message.builder()
                .sender(sender)
                .receiver(receiver)
                .imageFilename(filename)
                .encryptedKey(encryptedKey)
                .senderLat(lat)
                .senderLon(lon)
                .radiusMeters(radius)
                .build();
        return messageRepository.save(message);
    }

    public List<Message> getInbox(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        return messageRepository.findByReceiverIdOrderByCreatedAtDesc(user.getId());
    }

    public Message getMessage(UUID id, String username) {
        Message message = messageRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Message not found"));
        if (!message.getReceiver().getUsername().equals(username)) {
            throw new SecurityException("Access denied");
        }
        return message;
    }

    public Path getImagePath(UUID id, String username) {
        Message message = getMessage(id, username);
        return imageStoragePath.resolve(message.getImageFilename());
    }

    private void validateImageType(MultipartFile file) throws IOException {
        byte[] header = new byte[8];
        try (InputStream is = file.getInputStream()) {
            is.read(header);
        }
        // PNG magic bytes: 89 50 4E 47
        // BMP magic bytes: 42 4D
        boolean isPng = header[0] == (byte)0x89 && header[1] == 0x50 && header[2] == 0x4E && header[3] == 0x47;
        boolean isBmp = header[0] == 0x42 && header[1] == 0x4D;
        if (!isPng && !isBmp) {
            throw new IllegalArgumentException("Only PNG and BMP images are accepted");
        }
    }

    private BufferedImage readImage(InputStream is) throws IOException {
        BufferedImage img = ImageIO.read(is);
        if (img == null) throw new IllegalArgumentException("Cannot read image");
        return img;
    }
}
```

**Step 2: Create MessageController**

```java
package com.securechat.controller;

import com.securechat.model.Message;
import com.securechat.service.MessageService;
import org.springframework.core.io.PathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/messages")
public class MessageController {

    private final MessageService messageService;

    public MessageController(MessageService messageService) {
        this.messageService = messageService;
    }

    @PostMapping("/send")
    public ResponseEntity<?> send(
            @AuthenticationPrincipal UserDetails user,
            @RequestParam("image") MultipartFile image,
            @RequestParam("encryptedPayload") String encryptedPayload,
            @RequestParam("encryptedKey") String encryptedKey,
            @RequestParam("receiverUsername") String receiverUsername,
            @RequestParam("lat") double lat,
            @RequestParam("lon") double lon,
            @RequestParam(value = "radius", defaultValue = "50") int radius) throws Exception {
        Message msg = messageService.sendMessage(user.getUsername(), receiverUsername,
                image, encryptedPayload, encryptedKey, lat, lon, radius);
        return ResponseEntity.ok(Map.of("messageId", msg.getId()));
    }

    @GetMapping("/inbox")
    public ResponseEntity<List<Map<String, Object>>> inbox(
            @AuthenticationPrincipal UserDetails user) {
        List<Map<String, Object>> result = messageService.getInbox(user.getUsername())
                .stream()
                .map(m -> Map.of(
                        "id", m.getId(),
                        "senderUsername", m.getSender().getUsername(),
                        "createdAt", m.getCreatedAt()
                ))
                .toList();
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getMessage(
            @AuthenticationPrincipal UserDetails user,
            @PathVariable UUID id) {
        Message msg = messageService.getMessage(id, user.getUsername());
        return ResponseEntity.ok(Map.of(
                "encryptedKey", msg.getEncryptedKey(),
                "senderLat", msg.getSenderLat(),
                "senderLon", msg.getSenderLon(),
                "radiusMeters", msg.getRadiusMeters()
        ));
    }

    @GetMapping("/{id}/image")
    public ResponseEntity<Resource> getImage(
            @AuthenticationPrincipal UserDetails user,
            @PathVariable UUID id) throws Exception {
        Path imagePath = messageService.getImagePath(id, user.getUsername());
        Resource resource = new PathResource(imagePath);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .body(resource);
    }
}
```

**Step 3: Create LocationController**

```java
package com.securechat.controller;

import com.securechat.model.Message;
import com.securechat.service.GeoLocationService;
import com.securechat.service.MessageService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/location")
public class LocationController {

    private final GeoLocationService geoLocationService;
    private final MessageService messageService;

    public LocationController(GeoLocationService geoLocationService, MessageService messageService) {
        this.geoLocationService = geoLocationService;
        this.messageService = messageService;
    }

    @PostMapping("/verify")
    public ResponseEntity<?> verify(
            @AuthenticationPrincipal UserDetails user,
            @RequestBody Map<String, Object> body) {
        double lat = ((Number) body.get("lat")).doubleValue();
        double lon = ((Number) body.get("lon")).doubleValue();
        UUID messageId = UUID.fromString((String) body.get("messageId"));

        if (!geoLocationService.isCoarseValid(lat, lon)) {
            return ResponseEntity.ok(Map.of("valid", false, "reason", "Invalid coordinates"));
        }

        Message message = messageService.getMessage(messageId, user.getUsername());
        boolean valid = geoLocationService.isWithinRadius(
                message.getSenderLat(), message.getSenderLon(),
                lat, lon, message.getRadiusMeters());

        return ResponseEntity.ok(Map.of("valid", valid));
    }
}
```

**Step 4: Create UserController**

```java
package com.securechat.controller;

import com.securechat.repository.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserRepository userRepository;

    public UserController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @GetMapping("/{username}")
    public ResponseEntity<?> getPublicKey(@PathVariable String username) {
        return userRepository.findByUsername(username)
                .map(u -> ResponseEntity.ok(Map.of("publicKey", u.getPublicKey())))
                .orElse(ResponseEntity.notFound().build());
    }
}
```

**Step 5: Commit**
```bash
git add backend/src/main/java/com/securechat/service/MessageService.java \
         backend/src/main/java/com/securechat/controller/
git commit -m "feat: add MessageService and all REST controllers"
```

---

## Task 12: Global Exception Handler

**Files:**
- Create: `backend/src/main/java/com/securechat/config/GlobalExceptionHandler.java`

**Step 1: Create handler**

```java
package com.securechat.config;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<?> handleBadCredentials(BadCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "Invalid username or password"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<?> handleForbidden(SecurityException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "Access denied"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> handleValidation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .findFirst().orElse("Validation failed");
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleGeneral(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Internal server error"));
    }
}
```

**Step 2: Commit**
```bash
git add backend/src/main/java/com/securechat/config/GlobalExceptionHandler.java
git commit -m "feat: add global exception handler for clean error responses"
```

---

## Task 13: Dockerfile for Spring Boot

**Files:**
- Create: `backend/Dockerfile`

**Step 1: Create Dockerfile**

```dockerfile
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN apk add --no-cache maven && mvn clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
COPY --from=builder /app/target/*.jar app.jar
RUN mkdir -p /var/app/images && chown appuser:appgroup /var/app/images
USER appuser
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

**Step 2: Commit**
```bash
git add backend/Dockerfile
git commit -m "feat: add multi-stage Dockerfile for Spring Boot app"
```

---

## Task 14: Docker Compose + NGINX Config

**Files:**
- Create: `docker-compose.yml`
- Create: `.env.example`
- Create: `nginx/nginx.conf`

**Step 1: Create docker-compose.yml**

```yaml
version: '3.8'

services:
  postgres:
    image: postgres:15
    environment:
      POSTGRES_DB: securechat
      POSTGRES_USER: chatuser
      POSTGRES_PASSWORD: ${DB_PASSWORD}
    volumes:
      - postgres_data:/var/lib/postgresql/data
    networks:
      - internal
    restart: unless-stopped
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U chatuser -d securechat"]
      interval: 10s
      timeout: 5s
      retries: 5

  app:
    build: ./backend
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/securechat
      SPRING_DATASOURCE_USERNAME: chatuser
      SPRING_DATASOURCE_PASSWORD: ${DB_PASSWORD}
      JWT_SECRET: ${JWT_SECRET}
      IMAGE_STORAGE_PATH: /var/app/images
    volumes:
      - image_data:/var/app/images
      - ./frontend:/app/static
    depends_on:
      postgres:
        condition: service_healthy
    networks:
      - internal
    restart: unless-stopped

  nginx:
    image: nginx:alpine
    ports:
      - "80:80"
      - "443:443"
    volumes:
      - ./nginx/nginx.conf:/etc/nginx/nginx.conf:ro
      - /etc/letsencrypt:/etc/letsencrypt:ro
      - ./frontend:/var/www/html:ro
    depends_on:
      - app
    networks:
      - internal
      - external
    restart: unless-stopped

networks:
  internal:
    driver: bridge
  external:
    driver: bridge

volumes:
  postgres_data:
  image_data:
```

**Step 2: Create .env.example**

```bash
DB_PASSWORD=change_this_strong_password
JWT_SECRET=change_this_to_a_random_64_char_string_for_production
```

**Step 3: Create nginx/nginx.conf**

```nginx
events {
    worker_connections 1024;
}

http {
    # Rate limiting
    limit_req_zone $binary_remote_addr zone=auth:10m rate=5r/m;
    limit_req_zone $binary_remote_addr zone=api:10m rate=30r/m;

    server {
        listen 80;
        server_name _;
        return 301 https://$host$request_uri;
    }

    server {
        listen 443 ssl http2;
        server_name _;

        ssl_certificate     /etc/letsencrypt/live/YOURDOMAIN/fullchain.pem;
        ssl_certificate_key /etc/letsencrypt/live/YOURDOMAIN/privkey.pem;
        ssl_protocols       TLSv1.2 TLSv1.3;
        ssl_ciphers         HIGH:!aNULL:!MD5;
        ssl_prefer_server_ciphers on;

        # Security headers
        add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
        add_header X-Frame-Options "DENY" always;
        add_header X-Content-Type-Options "nosniff" always;
        add_header Referrer-Policy "strict-origin-when-cross-origin" always;
        add_header Content-Security-Policy "default-src 'self'; script-src 'self' 'unsafe-inline' cdn.jsdelivr.net unpkg.com; style-src 'self' 'unsafe-inline'; img-src 'self' data: tile.openstreetmap.org *.tile.openstreetmap.org" always;

        root /var/www/html;

        # Serve frontend
        location / {
            try_files $uri $uri/ /index.html;
        }

        # Auth endpoints — stricter rate limit
        location /api/auth/ {
            limit_req zone=auth burst=10 nodelay;
            proxy_pass http://app:8080;
            proxy_set_header Host $host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
            proxy_set_header X-Forwarded-Proto $scheme;
        }

        # API endpoints
        location /api/ {
            limit_req zone=api burst=50 nodelay;
            proxy_pass http://app:8080;
            proxy_set_header Host $host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
            proxy_set_header X-Forwarded-Proto $scheme;
            client_max_body_size 10M;
        }
    }
}
```

**Step 4: Commit**
```bash
git add docker-compose.yml .env.example nginx/
git commit -m "feat: add Docker Compose stack and NGINX config with rate limiting and security headers"
```

---

## Task 15: Frontend — HTML + Alpine.js

**Files:**
- Create: `frontend/index.html`
- Create: `frontend/app.js`
- Create: `frontend/style.css`

**Step 1: Create index.html**

```html
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>SecureChat</title>
    <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css"/>
    <link rel="stylesheet" href="style.css">
</head>
<body>
    <div id="app" x-data="secureChat()" x-init="init()">

        <!-- Navigation -->
        <nav x-show="auth.loggedIn">
            <span x-text="'Hello, ' + auth.username"></span>
            <a href="#" @click="navigate('inbox')">Inbox</a>
            <a href="#" @click="navigate('send')">Send</a>
            <a href="#" @click="logout()">Logout</a>
        </nav>

        <!-- Login View -->
        <div x-show="view === 'login'" class="card">
            <h2>Login</h2>
            <input x-model="form.username" type="text" placeholder="Username">
            <input x-model="form.password" type="password" placeholder="Password">
            <button @click="login()">Login</button>
            <p>No account? <a href="#" @click="navigate('register')">Register</a></p>
            <p class="error" x-text="error"></p>
        </div>

        <!-- Register View -->
        <div x-show="view === 'register'" class="card">
            <h2>Register</h2>
            <input x-model="form.username" type="text" placeholder="Username">
            <input x-model="form.password" type="password" placeholder="Password (min 8 chars)">
            <button @click="register()">Register</button>
            <p>Have an account? <a href="#" @click="navigate('login')">Login</a></p>
            <p class="error" x-text="error"></p>
            <p class="info" x-text="info"></p>
        </div>

        <!-- Inbox View -->
        <div x-show="view === 'inbox'" class="card">
            <h2>Inbox</h2>
            <div x-show="messages.length === 0" class="empty">No messages yet.</div>
            <template x-for="msg in messages" :key="msg.id">
                <div class="message-item" @click="openMessage(msg.id)">
                    <span x-text="'From: ' + msg.senderUsername"></span>
                    <span x-text="new Date(msg.createdAt).toLocaleString()"></span>
                </div>
            </template>
        </div>

        <!-- Send View -->
        <div x-show="view === 'send'" class="card">
            <h2>Send Encrypted Message</h2>
            <input x-model="sendForm.receiver" type="text" placeholder="Receiver username">
            <textarea x-model="sendForm.message" placeholder="Your secret message"></textarea>
            <input type="file" @change="handleImageUpload($event)" accept=".png,.bmp">
            <div id="map" style="height:300px;"></div>
            <p class="info" x-show="sendForm.lat">
                Location set: <span x-text="sendForm.lat.toFixed(5) + ', ' + sendForm.lon.toFixed(5)"></span>
            </p>
            <button @click="sendMessage()" :disabled="sending">
                <span x-text="sending ? 'Encrypting & Sending...' : 'Send Message'"></span>
            </button>
            <p class="error" x-text="error"></p>
            <p class="info" x-text="info"></p>
        </div>

        <!-- View Message -->
        <div x-show="view === 'view'" class="card">
            <h2>Decrypt Message</h2>
            <button @click="decryptMessage()">Unlock with My Location</button>
            <div x-show="decrypted" class="decrypted-msg">
                <h3>Message:</h3>
                <p x-text="decrypted"></p>
            </div>
            <p class="error" x-text="error"></p>
            <a href="#" @click="navigate('inbox')">&larr; Back to inbox</a>
        </div>

    </div>

    <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
    <script src="https://cdn.jsdelivr.net/npm/node-forge@1.3.1/dist/forge.min.js"></script>
    <script defer src="https://cdn.jsdelivr.net/npm/alpinejs@3.x.x/dist/cdn.min.js"></script>
    <script src="app.js"></script>
</body>
</html>
```

**Step 2: Create app.js**

```javascript
function secureChat() {
    return {
        view: 'login',
        auth: { loggedIn: false, username: null, privateKey: null },
        form: { username: '', password: '' },
        sendForm: { receiver: '', message: '', image: null, lat: null, lon: null },
        messages: [],
        currentMessageId: null,
        decrypted: null,
        error: '',
        info: '',
        sending: false,
        map: null,

        async init() {
            // Check if already logged in (cookie exists)
            try {
                const res = await fetch('/api/messages/inbox');
                if (res.ok) {
                    // Session active — but we don't have private key in memory
                    // User must re-login to derive private key
                }
            } catch(e) {}
        },

        navigate(view) {
            this.error = '';
            this.info = '';
            this.view = view;
            if (view === 'inbox') this.loadInbox();
            if (view === 'send') this.$nextTick(() => this.initMap());
        },

        // --- KEY DERIVATION ---
        async derivePrivateKey(username, password) {
            const salt = new TextEncoder().encode(username + ':securechat:v1');
            const keyMaterial = await crypto.subtle.importKey(
                'raw', new TextEncoder().encode(password),
                'PBKDF2', false, ['deriveBits']
            );
            const bits = await crypto.subtle.deriveBits(
                { name: 'PBKDF2', salt, iterations: 310000, hash: 'SHA-256' },
                keyMaterial, 256
            );
            // Use derived bits as seed for RSA key generation via forge
            const seed = Array.from(new Uint8Array(bits)).map(b => String.fromCharCode(b)).join('');
            const prng = forge.random.createInstance();
            prng.seedFileSync = () => seed;
            return new Promise((resolve, reject) => {
                forge.pki.rsa.generateKeyPair({ bits: 2048, workers: -1, prng }, (err, keypair) => {
                    if (err) reject(err);
                    else resolve(keypair);
                });
            });
        },

        // --- REGISTER ---
        async register() {
            this.error = '';
            this.info = 'Generating cryptographic keys... (this takes a moment)';
            try {
                const keypair = await this.derivePrivateKey(this.form.username, this.form.password);
                const publicKeyPem = forge.pki.publicKeyToPem(keypair.publicKey);
                const res = await fetch('/api/auth/register', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        username: this.form.username,
                        password: this.form.password,
                        publicKey: publicKeyPem
                    })
                });
                const data = await res.json();
                if (!res.ok) { this.error = data.error; this.info = ''; return; }
                this.info = 'Registered! You can now login.';
                setTimeout(() => this.navigate('login'), 1500);
            } catch(e) {
                this.error = e.message;
                this.info = '';
            }
        },

        // --- LOGIN ---
        async login() {
            this.error = '';
            try {
                const keypair = await this.derivePrivateKey(this.form.username, this.form.password);
                const res = await fetch('/api/auth/login', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ username: this.form.username, password: this.form.password })
                });
                const data = await res.json();
                if (!res.ok) { this.error = data.error; return; }
                this.auth = { loggedIn: true, username: data.username, privateKey: keypair.privateKey };
                this.navigate('inbox');
            } catch(e) {
                this.error = e.message;
            }
        },

        // --- LOGOUT ---
        async logout() {
            await fetch('/api/auth/logout', { method: 'POST' });
            this.auth = { loggedIn: false, username: null, privateKey: null };
            this.view = 'login';
        },

        // --- INBOX ---
        async loadInbox() {
            const res = await fetch('/api/messages/inbox');
            if (res.ok) this.messages = await res.json();
        },

        // --- MAP ---
        initMap() {
            if (this.map) { this.map.remove(); this.map = null; }
            this.map = L.map('map').setView([20.5937, 78.9629], 5);
            L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png').addTo(this.map);
            let marker = null;
            this.map.on('click', (e) => {
                if (marker) marker.remove();
                marker = L.marker(e.latlng).addTo(this.map);
                this.sendForm.lat = e.latlng.lat;
                this.sendForm.lon = e.latlng.lng;
            });
        },

        handleImageUpload(event) {
            this.sendForm.image = event.target.files[0];
        },

        // --- SEND MESSAGE ---
        async sendMessage() {
            this.error = '';
            this.info = '';
            if (!this.sendForm.lat) { this.error = 'Please click on the map to set geo-lock location'; return; }
            if (!this.sendForm.image) { this.error = 'Please select an image'; return; }
            if (!this.sendForm.message) { this.error = 'Message cannot be empty'; return; }
            this.sending = true;
            try {
                // Get receiver public key
                const keyRes = await fetch(`/api/users/${this.sendForm.receiver}`);
                if (!keyRes.ok) { this.error = 'Receiver not found'; this.sending = false; return; }
                const { publicKey: pubKeyPem } = await keyRes.json();
                const receiverPublicKey = forge.pki.publicKeyFromPem(pubKeyPem);

                // AES-256-GCM encrypt message
                const aesKey = new Uint8Array(32);
                crypto.getRandomValues(aesKey);
                const iv = new Uint8Array(12);
                crypto.getRandomValues(iv);
                const importedKey = await crypto.subtle.importKey('raw', aesKey, 'AES-GCM', false, ['encrypt']);
                const ciphertext = await crypto.subtle.encrypt(
                    { name: 'AES-GCM', iv },
                    importedKey,
                    new TextEncoder().encode(this.sendForm.message)
                );

                // Bundle payload: iv(12) + ciphertext
                const payload = new Uint8Array(12 + ciphertext.byteLength);
                payload.set(iv, 0);
                payload.set(new Uint8Array(ciphertext), 12);
                const payloadBase64 = btoa(String.fromCharCode(...payload));

                // RSA encrypt AES key with receiver's public key
                const aesKeyBytes = forge.util.createBuffer(String.fromCharCode(...aesKey));
                const encryptedKeyBytes = receiverPublicKey.encrypt(aesKeyBytes.data, 'RSA-OAEP', {
                    md: forge.md.sha256.create()
                });
                const encryptedKeyBase64 = btoa(encryptedKeyBytes);

                // Upload
                const formData = new FormData();
                formData.append('image', this.sendForm.image);
                formData.append('encryptedPayload', payloadBase64);
                formData.append('encryptedKey', encryptedKeyBase64);
                formData.append('receiverUsername', this.sendForm.receiver);
                formData.append('lat', this.sendForm.lat);
                formData.append('lon', this.sendForm.lon);
                formData.append('radius', '50');

                const res = await fetch('/api/messages/send', { method: 'POST', body: formData });
                const data = await res.json();
                if (!res.ok) { this.error = data.error; this.sending = false; return; }
                this.info = 'Message sent successfully!';
                this.sendForm = { receiver: '', message: '', image: null, lat: null, lon: null };
            } catch(e) {
                this.error = 'Error: ' + e.message;
            }
            this.sending = false;
        },

        // --- OPEN + DECRYPT MESSAGE ---
        async openMessage(id) {
            this.currentMessageId = id;
            this.decrypted = null;
            this.error = '';
            this.navigate('view');
        },

        async decryptMessage() {
            this.error = '';
            if (!this.auth.privateKey) { this.error = 'Session expired. Please login again.'; return; }
            try {
                // Get current location
                const pos = await new Promise((resolve, reject) =>
                    navigator.geolocation.getCurrentPosition(resolve, reject)
                );
                const { latitude: lat, longitude: lon } = pos.coords;

                // Coarse server verify
                const verifyRes = await fetch('/api/location/verify', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ lat, lon, messageId: this.currentMessageId })
                });
                const { valid } = await verifyRes.json();
                if (!valid) { this.error = 'Location check failed (server)'; return; }

                // Get message metadata
                const msgRes = await fetch(`/api/messages/${this.currentMessageId}`);
                const msg = await msgRes.json();

                // Client-side fine geo check (Haversine)
                const dist = haversine(msg.senderLat, msg.senderLon, lat, lon);
                if (dist > msg.radiusMeters) {
                    this.error = `You are ${Math.round(dist)}m away. Must be within ${msg.radiusMeters}m.`;
                    return;
                }

                // Download stego image and extract payload
                const imgRes = await fetch(`/api/messages/${this.currentMessageId}/image`);
                const imgBlob = await imgRes.blob();
                const payload = await extractLsbPayload(imgBlob);

                // RSA decrypt AES key
                const encryptedKeyBytes = atob(msg.encryptedKey);
                const aesKeyBytes = this.auth.privateKey.decrypt(encryptedKeyBytes, 'RSA-OAEP', {
                    md: forge.md.sha256.create()
                });
                const aesKey = new Uint8Array(aesKeyBytes.split('').map(c => c.charCodeAt(0)));

                // AES-GCM decrypt
                const iv = payload.slice(0, 12);
                const ciphertext = payload.slice(12);
                const importedKey = await crypto.subtle.importKey('raw', aesKey, 'AES-GCM', false, ['decrypt']);
                const plaintext = await crypto.subtle.decrypt({ name: 'AES-GCM', iv }, importedKey, ciphertext);
                this.decrypted = new TextDecoder().decode(plaintext);

            } catch(e) {
                this.error = 'Decryption failed: ' + e.message;
            }
        }
    };
}

// --- Haversine (client-side fine check) ---
function haversine(lat1, lon1, lat2, lon2) {
    const R = 6371000;
    const dLat = (lat2 - lat1) * Math.PI / 180;
    const dLon = (lon2 - lon1) * Math.PI / 180;
    const a = Math.sin(dLat/2)**2 +
              Math.cos(lat1 * Math.PI/180) * Math.cos(lat2 * Math.PI/180) * Math.sin(dLon/2)**2;
    return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
}

// --- LSB Extraction (client-side) ---
async function extractLsbPayload(imageBlob) {
    return new Promise((resolve, reject) => {
        const img = new Image();
        img.onload = () => {
            const canvas = document.createElement('canvas');
            canvas.width = img.width;
            canvas.height = img.height;
            const ctx = canvas.getContext('2d');
            ctx.drawImage(img, 0, 0);
            const data = ctx.getImageData(0, 0, img.width, img.height).data;

            // Read header (first 32 bits = payload length)
            let headerBits = '';
            for (let i = 0; i < 32; i++) {
                const pixelIdx = Math.floor(i / 3) * 4;
                const channel = i % 3;
                headerBits += (data[pixelIdx + channel] & 1).toString();
            }
            const payloadLength = parseInt(headerBits, 2);
            if (payloadLength <= 0 || payloadLength > 10_000_000) {
                reject(new Error('Invalid payload length: ' + payloadLength));
                return;
            }

            // Read payload bits
            const totalBits = (4 + payloadLength) * 8;
            const result = new Uint8Array(payloadLength);
            let bitIdx = 32; // skip header
            let resultBit = 0;
            for (let i = 32; i < totalBits && resultBit < payloadLength * 8; i++) {
                const pixelIdx = Math.floor(i / 3) * 4;
                const channel = i % 3;
                const bit = data[pixelIdx + channel] & 1;
                if (bit) result[Math.floor(resultBit / 8)] |= (1 << (7 - (resultBit % 8)));
                resultBit++;
            }
            resolve(result);
        };
        img.onerror = reject;
        img.src = URL.createObjectURL(imageBlob);
    });
}
```

**Step 3: Create style.css**

```css
*, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }

body {
    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
    background: #0f172a;
    color: #e2e8f0;
    min-height: 100vh;
}

nav {
    background: #1e293b;
    padding: 1rem 2rem;
    display: flex;
    gap: 1.5rem;
    align-items: center;
    border-bottom: 1px solid #334155;
}

nav a { color: #94a3b8; text-decoration: none; }
nav a:hover { color: #e2e8f0; }
nav span { margin-right: auto; font-weight: 600; }

.card {
    max-width: 600px;
    margin: 2rem auto;
    padding: 2rem;
    background: #1e293b;
    border-radius: 12px;
    border: 1px solid #334155;
    display: flex;
    flex-direction: column;
    gap: 1rem;
}

h2 { font-size: 1.5rem; color: #f1f5f9; }
h3 { color: #94a3b8; }

input, textarea {
    background: #0f172a;
    border: 1px solid #475569;
    border-radius: 8px;
    padding: 0.75rem 1rem;
    color: #e2e8f0;
    font-size: 1rem;
    width: 100%;
}

input:focus, textarea:focus {
    outline: none;
    border-color: #6366f1;
}

textarea { min-height: 100px; resize: vertical; }

button {
    background: #6366f1;
    color: white;
    border: none;
    padding: 0.75rem 1.5rem;
    border-radius: 8px;
    font-size: 1rem;
    cursor: pointer;
    font-weight: 600;
    transition: background 0.2s;
}

button:hover:not(:disabled) { background: #4f46e5; }
button:disabled { background: #475569; cursor: not-allowed; }

.error { color: #f87171; font-size: 0.9rem; }
.info  { color: #34d399; font-size: 0.9rem; }
.empty { color: #64748b; text-align: center; padding: 2rem; }

.message-item {
    padding: 1rem;
    background: #0f172a;
    border-radius: 8px;
    border: 1px solid #334155;
    cursor: pointer;
    display: flex;
    justify-content: space-between;
    transition: border-color 0.2s;
}

.message-item:hover { border-color: #6366f1; }

.decrypted-msg {
    background: #0f172a;
    border: 1px solid #34d399;
    border-radius: 8px;
    padding: 1rem;
    margin-top: 1rem;
}

.decrypted-msg p { color: #f1f5f9; line-height: 1.6; white-space: pre-wrap; }

a { color: #6366f1; }
a:hover { color: #818cf8; }
```

**Step 4: Commit**
```bash
git add frontend/
git commit -m "feat: add frontend with Alpine.js, forge.js crypto, Leaflet map, and LSB extraction"
```

---

## Task 16: Create GitHub Repository and Push

**Step 1: Create GitHub repository**
```bash
cd ~/Desktop/Secure_chat
gh repo create Secure_chat --public --description "Geo-locked steganographic secure chat — Spring Boot + Docker + Alpine.js" --source . --remote origin
```

**Step 2: Push all commits**
```bash
git push -u origin master
```

**Step 3: Verify**
```bash
gh repo view --web
```

---

## Task 17: Build Verification

**Step 1: Start Postgres locally for testing**
```bash
cd ~/Desktop/Secure_chat
docker compose up postgres -d
```

**Step 2: Run all backend tests**
```bash
cd backend
mvn test
```
Expected: All tests PASS.

**Step 3: Build the JAR**
```bash
mvn clean package -DskipTests
```
Expected: `target/secure-chat-0.0.1-SNAPSHOT.jar` created.

**Step 4: Build Docker image**
```bash
cd ~/Desktop/Secure_chat
docker build -t secure-chat ./backend
```
Expected: Image built successfully.

**Step 5: Run full stack**
```bash
cp .env.example .env
# Edit .env to set real values
docker compose up -d
```

**Step 6: Smoke test**
```bash
curl -s http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"testuser","password":"testpass1","publicKey":"TESTKEY"}'
```
Expected: `{"message":"Registered successfully"}`

---

## Azure Deployment Checklist

After all local tests pass:

```
1. SSH into Azure VM
2. sudo apt update && sudo apt install -y docker.io docker-compose-v2 git
3. sudo usermod -aG docker $USER && newgrp docker
4. git clone https://github.com/ameyaatmaa/Secure_chat
5. cd Secure_chat && cp .env.example .env && nano .env  (set real secrets)
6. docker compose up -d
7. sudo apt install certbot
8. certbot certonly --standalone -d yourdomain.com
9. Update nginx/nginx.conf with your domain
10. docker compose restart nginx
```
