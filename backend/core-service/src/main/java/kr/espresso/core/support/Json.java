package kr.espresso.core.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class Json {
    private final ObjectMapper om;
    public Json(ObjectMapper om) { this.om = om; }
    public String write(Object o) {
        try { return o == null ? null : om.writeValueAsString(o); } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
