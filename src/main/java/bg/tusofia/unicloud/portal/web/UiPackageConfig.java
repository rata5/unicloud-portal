package bg.tusofia.unicloud.portal.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Other teams' pages and builds read /ui/v1 (the @unicloud/ui package and its stylesheet) from
 * their own origins: {@code Access-Control-Allow-Origin: *}, reads only, no cookies.
 */
@Configuration
public class UiPackageConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/ui/v1/**")
                .allowedOrigins("*")
                .allowedMethods("GET", "HEAD")
                .allowCredentials(false);
    }
}
