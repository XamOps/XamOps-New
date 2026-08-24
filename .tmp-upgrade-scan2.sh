#!/bin/bash
cd "$(dirname "$0")" || exit 1

echo "=== Renamed / removed config properties (Boot 3 & 4) ==="
for key in "spring.redis" "spring.datasource.initialization-mode" "spring.mvc.pathmatch" "server.max-http-header-size" "spring.jpa.hibernate.use-new-id-generator" "spring.sleuth" "management.metrics.export" "spring.cloud.loadbalancer.ribbon" "spring.data.cassandra" "spring.security.oauth2" "spring.mvc.servlet.path" "spring.main.allow-circular-references" "spring.kafka" "spring.flyway" "eureka." "spring.cloud.gateway" "spring.thymeleaf" "spring.session.store-type" "server.servlet.session" "spring.jpa.properties.hibernate.dialect" "spring.jpa.open-in-view" "logging.pattern" "spring.config.import" "spring.profiles.active" "spring.autoconfigure.exclude" "spring.mvc.async" "spring.web.resources"; do
  n=$(grep -rl -- "$key" --include="application*.properties" --include="application*.yml" --include="bootstrap*.properties" --include="bootstrap*.yml" . 2>/dev/null | grep -v '/target/' | grep -v node_modules | wc -l | tr -d ' ')
  if [ "$n" != "0" ]; then printf "  %-48s %s file(s)\n" "$key" "$n"; fi
done

echo
echo "=== Hibernate / JPA specifics ==="
grep -rn "org.hibernate" --include="*.java" . 2>/dev/null | grep -v '/target/' | grep "^\./" | head -20
echo "--- @Type usages"
grep -rn "@Type(" --include="*.java" . 2>/dev/null | grep -v '/target/' | head -10
echo "--- GenerationType usage counts"
grep -rho "GenerationType\.[A-Z]*" --include="*.java" . 2>/dev/null | sort | uniq -c
echo "--- native/JPQL queries with nativeQuery"
grep -rl "nativeQuery *= *true" --include="*.java" . 2>/dev/null | grep -v '/target/' | wc -l
echo "--- @Query annotated repo files"
grep -rl "@Query" --include="*.java" . 2>/dev/null | grep -v '/target/' | wc -l

echo
echo "=== Other risky deps present in poms ==="
for dep in springfox swagger flyway-core spring-kafka kubernetes-client thymeleaf devtools spring-cloud-starter-gateway netflix-eureka-server netflix-eureka-client circuitbreaker-resilience4j spring-session-data-redis awspring lombok jjwt itext poi pdfbox opencsv; do
  n=$(grep -rl -- "$dep" --include="pom.xml" . 2>/dev/null | grep -v '/target/' | wc -l | tr -d ' ')
  if [ "$n" != "0" ]; then printf "  %-42s in %s pom(s)\n" "$dep" "$n"; fi
done

echo
echo "=== servlet API usage detail (javax.servlet) ==="
grep -rho "^import javax\.servlet\.[A-Za-z.]*" --include="*.java" . 2>/dev/null | sort | uniq -c | sort -rn | head -20
