package azrefarc.springboot;

import javax.servlet.Filter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import com.microsoft.applicationinsights.TelemetryConfiguration;
import com.microsoft.applicationinsights.web.internal.WebRequestTrackingFilter;

@SpringBootApplication
@Configuration
public class SpringBootSystemApplication {

	public static void main(String[] args) {
		//TelemetryConfiguration.getActive().setInstrumentationKey("580479dc-9dca-4c04-88d3-2e7c3cdc1da2");
		
		SpringApplication.run(SpringBootSystemApplication.class, args);
	}
	
    //Set AI Web Request Tracking Filter
    @Bean
    public FilterRegistrationBean aiFilterRegistration(@Value("${spring.application.name:application}") String applicationName) {
        FilterRegistrationBean registration = new FilterRegistrationBean();
        registration.setFilter(new WebRequestTrackingFilter(applicationName));
        registration.setName("webRequestTrackingFilter");
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    } 

    //Set up AI Web Request Tracking Filter
    @Bean(name = "WebRequestTrackingFilter")
    public Filter webRequestTrackingFilter(@Value("${spring.application.name:application}") String applicationName) {
        return new WebRequestTrackingFilter(applicationName);
    }   
}

