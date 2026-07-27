package kr.ac.pusan.pickle.config;

import kr.ac.pusan.pickle.security.ReauthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * First (and so far only) MVC interceptor registration: the sudo-mode gate
 * needs the resolved handler method to read {@code @RequireReauth}, which a
 * servlet filter cannot see without duplicating handler mapping.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final ReauthInterceptor reauthInterceptor;

    public WebMvcConfig(ReauthInterceptor reauthInterceptor) {
        this.reauthInterceptor = reauthInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(reauthInterceptor).addPathPatterns("/api/v1/**");
    }
}
