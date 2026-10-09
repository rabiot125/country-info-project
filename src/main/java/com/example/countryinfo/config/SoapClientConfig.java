package com.example.countryinfo.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.transport.http.HttpUrlConnectionMessageSender;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SoapClientProperties.class)
public class SoapClientConfig {

    /** Package produced by jaxb-maven-plugin from src/main/resources/wsdl/CountryInfoService.wsdl. */
    static final String GENERATED_PACKAGE = "com.example.countryinfo.client.soap.generated";

    @Bean
    public Jaxb2Marshaller countryInfoMarshaller() {
        Jaxb2Marshaller marshaller = new Jaxb2Marshaller();
        marshaller.setContextPath(GENERATED_PACKAGE);
        return marshaller;
    }

    @Bean
    public WebServiceTemplate countryInfoWebServiceTemplate(Jaxb2Marshaller countryInfoMarshaller,
                                                            SoapClientProperties properties) {
        // Explicit timeouts: the JDK default is "wait forever", which would pin request
        // threads and let a slow upstream exhaust the Tomcat pool.
        HttpUrlConnectionMessageSender sender = new HttpUrlConnectionMessageSender();
        sender.setConnectionTimeout(properties.connectTimeout());
        sender.setReadTimeout(properties.readTimeout());

        WebServiceTemplate template = new WebServiceTemplate(countryInfoMarshaller);
        template.setDefaultUri(properties.endpointUrl());
        template.setMessageSender(sender);
        return template;
    }
}
