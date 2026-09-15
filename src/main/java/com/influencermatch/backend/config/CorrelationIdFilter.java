package com.influencermatch.backend.config;
import jakarta.servlet.*; import jakarta.servlet.http.*; import org.slf4j.MDC; import org.springframework.core.Ordered; import org.springframework.core.annotation.Order; import org.springframework.stereotype.Component;
import java.io.IOException; import java.util.UUID; import java.util.regex.Pattern;
@Component @Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter implements Filter {
    public static final String HEADER="X-Correlation-ID";
    private static final Pattern SAFE=Pattern.compile("[A-Za-z0-9._-]{1,128}");
    @Override public void doFilter(ServletRequest request,ServletResponse response,FilterChain chain)throws IOException,ServletException{
        HttpServletRequest req=(HttpServletRequest)request; HttpServletResponse res=(HttpServletResponse)response;
        String candidate=req.getHeader(HEADER); String traceId=candidate!=null&&SAFE.matcher(candidate).matches()?candidate:UUID.randomUUID().toString();
        MDC.put("traceId",traceId);res.setHeader(HEADER,traceId);try{chain.doFilter(request,response);}finally{MDC.remove("traceId");}
    }
}
