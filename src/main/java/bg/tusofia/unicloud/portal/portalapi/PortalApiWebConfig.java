package bg.tusofia.unicloud.portal.portalapi;

import bg.tusofia.unicloud.portal.accounts.application.PortalUser;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import jakarta.servlet.http.HttpServletRequest;

import java.util.List;

/**
 * A {@link PortalUser} parameter of a controller method is the signed-in user of the request, as
 * PortalUserFilter read it from the database. Without one: 401 {@code unauthenticated}.
 */
@Configuration
public class PortalApiWebConfig implements WebMvcConfigurer {

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(MethodParameter parameter) {
                return parameter.getParameterType() == PortalUser.class;
            }

            @Override
            public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
                                          NativeWebRequest request, WebDataBinderFactory binders) {
                PortalUser user = PortalUser.current(request.getNativeRequest(HttpServletRequest.class));
                if (user == null) {
                    throw PortalApiException.unauthenticated();
                }
                return user;
            }
        });
    }
}
