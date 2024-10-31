package vn.etc.jsonapi.entity;

import com.fasterxml.jackson.databind.JsonNode;

public class FunctionCode {

    private String functionCode;

    private JsonNode functionSetting;

    public String getFunctionCode() {
        return functionCode;
    }

    public JsonNode getFunctionSetting(){
        return functionSetting;
    }
}
