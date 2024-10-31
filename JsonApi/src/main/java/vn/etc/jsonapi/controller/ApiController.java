package vn.etc.jsonapi.controller;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.postgresql.util.PGobject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.sql.*;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.Date;
import java.util.stream.Collectors;

@RestController
public class ApiController {

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ObjectMapper objectMapper;

    // Map lưu trữ ánh xạ function_code -> stored_procedure_name
    private Map<String, JsonNode> functionCodeMapping;

    // Khởi tạo mapping khi ứng dụng khởi động
    @PostConstruct
    @Scheduled(fixedRate = 300000)  // 300s (5 phút) đọc lai db một lần
    public void initFunctionCodeMapping() {
        String sql = "SELECT function_code, function_setting FROM function_codes";

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
        functionCodeMapping = rows.stream()
                .collect(Collectors.toMap(
                        row -> (String) row.get("function_code"),
                        row -> {
                            try {
                                PGobject pgObject = (PGobject) row.get("function_setting");
                                return objectMapper.readTree(pgObject.getValue());
                            } catch (JsonProcessingException e) {
                                return objectMapper.createObjectNode();
                            }
                        }
                ));
    }

    @PostMapping("/api/xproc")
    public Object processJson(@RequestBody Map<String, Object> requestJson) {
        try {
            String functionCode = (String) requestJson.get("function_code");
            JsonNode functionSettings = functionCodeMapping.get(functionCode);
            if (functionSettings == null) {
                return Map.of("result", "error", "message", "Invalid function code");
            }

            JsonNode queryNode = functionSettings.get("query");
            if (queryNode == null) {
                return Map.of("result", "error", "message", "Invalid query, please contact admin - Empty query");
            }

            String query = queryNode.asText("").trim();
            if (query.isEmpty()) {
                return Map.of("result", "error", "message", "Invalid query, please contact admin - Empty query");
            }

            //để an toàn, cần kiểm tra câu query có chứa các nguy cơ tiềm ẩn hay không (xóa dữ liệu, query các bảng hệ thống...)
            if(query.contains(";") || query.contains("--")){
                return Map.of("result", "error", "message", "Risky query, please contact admin");
            }

            Object[] args = getQueryArgs(requestJson, functionSettings);
            long countQuestionMark = query.chars().filter(ch -> ch == '?').count();

            if (query.toUpperCase().startsWith("SELECT")){
                if(countQuestionMark!=args.length)
                    return Map.of("result", "error", "message", "Invalid query, please contact admin - parameter not match");
                return Map.of("result", "success",
                        "data", jdbcTemplate.queryForList(query, args));
            }else{
                if(countQuestionMark!=args.length+1)
                    return Map.of("result", "error", "message", "Invalid query, please contact admin - parameter not match");
                return Map.of("result", "success",
                        "data", callProcedure(query, args));
            }

        }catch (Exception e){
            return Map.of("result", "error", "message",e.getCause()!=null?e.getCause().getMessage():e.getMessage());
        }
    }

    @Transactional
    private List<Map<String, Object>> callProcedure(String query, Object[] params){
        if(!query.toLowerCase().startsWith("call"))
            query = "call " + query;

        String finalQuery = query;

        return jdbcTemplate.execute((Connection conn)->{

            List<Map<String, Object>> result = new ArrayList<>();

            CallableStatement callableStatement = conn.prepareCall(finalQuery);
            int i=0;
            for(;i<params.length;i++){
                if(params[i] instanceof Date){
                    callableStatement.setDate(i+1, new java.sql.Date(((Date) params[i]).getTime()));
                }else {
                    callableStatement.setObject(i + 1, params[i]);
                }
            }
            // Đăng ký kiểu refcursor cho tham số OUT
            callableStatement.registerOutParameter(i+1, Types.REF_CURSOR);  // Đăng ký kiểu REF_CURSOR cho tham số OUT

            callableStatement.execute();

            // Lấy kết quả từ refcursor
            ResultSet rs = (ResultSet) callableStatement.getObject(i+1); // Lấy kết quả từ refcursor
            ResultSetMetaData metaData = rs.getMetaData();
            int columnCount = metaData.getColumnCount();

            // Lặp qua kết quả ResultSet và đưa vào List<Map<String, Object>>
            while (rs.next()) {
                Map<String, Object> row = new HashMap<>();
                for (int col = 1; col <= columnCount; col++) {
                    row.put(metaData.getColumnName(col), rs.getObject(col));
                }
                result.add(row);
            }

            // Đóng ResultSet và CallableStatement
            rs.close();
            callableStatement.close();

            return result;

        });


//        Map<String, Object> result = jdbcTemplate.call(connection -> {
//            CallableStatement callableStatement = connection.prepareCall(finalQuery);
//            int i=0;
//            for(;i<params.length;i++){
//                if(params[i] instanceof Date){
//                    callableStatement.setDate(i+1, new java.sql.Date(((Date) params[i]).getTime()));
//                }else {
//                    callableStatement.setObject(i + 1, params[i]);
//                }
//            }
//            callableStatement.registerOutParameter(i+1, Types.REF_CURSOR);  // Đăng ký kiểu REF_CURSOR cho tham số OUT
//            return callableStatement;
//        }, List.of(
//                new SqlOutParameter("v_cursor", Types.REF_CURSOR, (ResultSet rs, int rowNum) -> {
//                    Map<String, Object> row = new HashMap<>();
//                    ResultSetMetaData metaData = rs.getMetaData();
//                    int columnCount = metaData.getColumnCount();
//                    for (int i = 1; i <= columnCount; i++) {
//                        row.put(metaData.getColumnName(i), rs.getObject(i));
//                    }
//                    return row;
//                })
//        ));
//
//        return (List<Map<String, Object>>) result.get("v_cursor");

    }
    private Object[] getQueryArgs(Map<String, Object> requestJson, JsonNode functionSettings){
        List<Object>args = new ArrayList<>();
        if(functionSettings.has("input_params")){
            JsonNode params = functionSettings.get("input_params");
            if(params.isArray() && params.size()>0) {
                for (JsonNode param :params) {
                    args.add(getRequestParam(requestJson, param));
                }
            }
        }else{
            args.addAll(requestJson.entrySet().stream()
                    .filter(entry -> !entry.getKey().equals("function_code")) // Loại bỏ entry có key là "function_code"
                    .map(Map.Entry::getValue) // Lấy giá trị của mỗi entry
                    .toList());
        }

        return args.toArray();
    }

    private Object getRequestParam(Map<String, Object> requestJson, JsonNode param){
        if(!param.has("name"))
            throw new RuntimeException("Invalid param setting, please contact admin - missing name");
        String paramName = param.get("name").asText();

        Object requestVal = requestJson.getOrDefault(paramName, null);
        if (requestVal==null)
            return null;

        String paramType = "string";
        if(param.has("type"))
            paramType = param.get("type").asText();


        switch (paramType.toLowerCase()){
            case "date":
            case "datetime":
                if(!param.has("format"))
                    throw new RuntimeException("Invalid param setting, please contact admin - parameter with date type is missing format");
                SimpleDateFormat dateFormat = new SimpleDateFormat(param.get("format").asText());
                dateFormat.setLenient(false);
                try {
                    return dateFormat.parse((String)requestVal);
                } catch (ParseException e) {
                    throw new RuntimeException("'" + paramName + "' - invalid date (" + param.get("format").asText() + ") '" + requestVal + "'");
                }
            case "int":
                if (requestVal instanceof Integer)
                    return requestVal;
                else
                    throw new RuntimeException("'" + paramName + "' - invalid Integer '" + requestVal + "'");
            case "float":
            case "double":
            case "number":
                if (requestVal instanceof Integer || requestVal instanceof Float || requestVal instanceof Double )
                    return requestVal;
                else
                    throw new RuntimeException("'" + paramName + "' - invalid Number '" + requestVal + "'");
            case "string":
                if (requestVal instanceof String)
                    return requestVal;
                else
                    throw new RuntimeException("'" + paramName + "' - invalid String '" + requestVal + "'");
            default:
                return requestVal;
        }
    }
}
