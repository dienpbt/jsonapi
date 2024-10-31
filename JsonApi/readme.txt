0. Deploy:
- sửa tham số kết nối database trong file application.properties

1. Tạo bảng trong database postgresql:
CREATE TABLE function_codes (
	function_code varchar NOT NULL,
	function_setting jsonb NOT NULL,
	CONSTRAINT function_codes_pk PRIMARY KEY (function_code)
);

Giải thích:
function_code: function code, client sẽ truyền giá trị này vào api
function_setting: chuỗi json chứa:
 - query: Câu lệnh query
 - input_params: mảng chứa danh sách các tham số sẽ truyền vào query. Chứa tên các tham số trong json do client truyền vào api

Định kỳ 5 phút, hệ thống sẽ đọc lại tham số từ database -> nếu cập nhật bảng này thì khoảng 5 phút sau sẽ có hiệu lực

2. Tạo dữ liệu config trong bảng function_codes:

ví dụ dữ liệu trong bảng function_codes:
function_code: get_something	
function_setting: {"query": "select * from test_function(?)", "input_params": ["abc"]}
test_function(?): test_function sẽ nhận một tham số
giá trị tham số này được lấy từ trường "abc" trong json của client truyền vào api

ví dụ code của test_function:
CREATE OR REPLACE FUNCTION test_function(employee_id INT)
RETURNS TABLE (id INT, name TEXT , department TEXT) AS $$
BEGIN
    RETURN QUERY
    select 5 as id, 'name5' as name, 'phòng X' as department
   union select 2 as id, 'name2' as name, 'phòng a' as department;
END;
$$ LANGUAGE plpgsql;

3. Gọi api:
Gửi POST tới http://server:port/api/xproc với Json chứa function_code và tham số khác
ví dụ:
{
  "function_code": "get_something",
  "abc":1
}
trong trường hợp json không có "abc", giá trị null sẽ được truyền vào câu lệnh "select * from test_function(?)"

4. Khuyến cáo:
- cần THẬN TRỌNG khi thiết lập tham số query
- nên dùng user có quyền chỉ đọc (và chỉ đọc một số bảng hạn chế) để kết nối database
- chỉ áp dụng api này cho các request đọc dữ liệu