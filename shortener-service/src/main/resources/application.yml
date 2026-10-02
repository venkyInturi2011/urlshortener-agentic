spring:
  application:
    name: shortener-service
  datasource:
    url: jdbc:h2:file:./data/shortener
    username: sa
  sql:
    init:
      mode: always
  mvc:
    problemdetails:
      enabled: true
management:
  endpoints:
    web:
      exposure:
        include: health
shortener:
  base-url: http://localhost:8080
  admin-key: ${SHORTENER_ADMIN_KEY:}
  ratelimit:
    capacity: 20
    refill-per-second: 1.0
