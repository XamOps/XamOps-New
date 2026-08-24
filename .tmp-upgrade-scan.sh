#!/bin/bash
cd "$(dirname "$0")" || exit 1

count_files() {
  local label="$1"; shift
  local pattern="$1"; shift
  local n
  n=$(grep -rl -- "$pattern" --include="*.java" . 2>/dev/null | grep -v '/target/' | wc -l | tr -d ' ')
  printf "%-42s %s\n" "$label" "$n"
}

echo "=== FILES matching Boot3/Boot4 breaking APIs ==="
count_files "WebSecurityConfigurerAdapter" "WebSecurityConfigurerAdapter"
count_files "authorizeRequests()" "authorizeRequests"
count_files "antMatchers()" "antMatchers"
count_files "EnableGlobalMethodSecurity" "EnableGlobalMethodSecurity"
count_files "WebMvcConfigurer" "WebMvcConfigurer"
count_files "@MockBean" "@MockBean"
count_files "@SpyBean" "@SpyBean"
count_files "org.hibernate.annotations.Type" "org.hibernate.annotations.Type"
count_files "org.hibernate.* imports" "^import org.hibernate"
count_files "springfox" "springfox"
count_files "io.swagger" "io.swagger"
count_files "javax.annotation.PostConstruct" "javax.annotation.PostConstruct"
count_files "javax.servlet.*" "^import javax.servlet"
count_files "javax.persistence.*" "^import javax.persistence"
count_files "javax.validation.*" "^import javax.validation"
count_files "jakarta.* (already migrated)" "^import jakarta"
count_files "SpringBootServletInitializer" "SpringBootServletInitializer"
count_files "RestTemplate" "RestTemplate"
count_files "WebClient" "WebClient"
count_files "PathMatchConfigurer/trailing slash" "setUseTrailingSlashMatch"
count_files "@EnableWebSecurity" "@EnableWebSecurity"
count_files "SecurityFilterChain (modern)" "SecurityFilterChain"
count_files "spring-cloud OpenFeign" "openfeign"
count_files "Netflix Eureka/Ribbon/Hystrix" "netflix"

echo
echo "=== resource/config/infra files ==="
printf "%-42s %s\n" "spring.factories" "$(find . -name spring.factories -not -path '*/target/*' | wc -l | tr -d ' ')"
printf "%-42s %s\n" "application*/bootstrap* config files" "$(find . \( -name 'application*.properties' -o -name 'application*.yml' -o -name 'bootstrap*.properties' -o -name 'bootstrap*.yml' \) -not -path '*/target/*' -not -path '*/node_modules/*' | wc -l | tr -d ' ')"
printf "%-42s %s\n" "Dockerfiles" "$(find . -name 'Dockerfile*' -not -path '*/node_modules/*' -not -path '*/target/*' | wc -l | tr -d ' ')"
printf "%-42s %s\n" "CI workflow files" "$(find .github -name '*.yml' -o -name '*.yaml' 2>/dev/null | wc -l | tr -d ' ')"
printf "%-42s %s\n" "pom.xml files" "$(find . -name pom.xml -not -path '*/target/*' -not -path '*/node_modules/*' | wc -l | tr -d ' ')"
printf "%-42s %s\n" "main java files" "$(find . -path '*/src/main/*' -name '*.java' -not -path '*/target/*' | wc -l | tr -d ' ')"
printf "%-42s %s\n" "test java files" "$(find . -path '*/src/test/*' -name '*.java' -not -path '*/target/*' | wc -l | tr -d ' ')"
printf "%-42s %s\n" "flyway migrations" "$(find . -path '*db/migration*' -name '*.sql' -not -path '*/target/*' | wc -l | tr -d ' ')"
