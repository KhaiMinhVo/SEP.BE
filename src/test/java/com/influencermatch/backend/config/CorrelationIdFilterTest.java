package com.influencermatch.backend.config;
import org.junit.jupiter.api.Test; import org.springframework.mock.web.*; import jakarta.servlet.*; import static org.assertj.core.api.Assertions.assertThat;
class CorrelationIdFilterTest {
 @Test void preservesSafeClientCorrelationId() throws Exception {MockHttpServletRequest request=new MockHttpServletRequest();request.addHeader(CorrelationIdFilter.HEADER,"client-123");MockHttpServletResponse response=new MockHttpServletResponse();new CorrelationIdFilter().doFilter(request,response,(req,res)->{});assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo("client-123");}
 @Test void replacesUnsafeCorrelationId() throws Exception {MockHttpServletRequest request=new MockHttpServletRequest();request.addHeader(CorrelationIdFilter.HEADER,"bad value\n");MockHttpServletResponse response=new MockHttpServletResponse();new CorrelationIdFilter().doFilter(request,response,(req,res)->{});assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isNotBlank().doesNotContain(" ","\n");}
}


