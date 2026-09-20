# Teenoi Coupon Standard

มาตรฐานปัจจุบันของคูปองระบบ Teenoi-Coupon

- ใช้ `STANDARD/(3)/index.html` เป็นแม่แบบหลัก
- หน้าตา/การจัดวางยึดจาก `e-Coupon/Teenoi/MK780944/(3)`
- QR ต้องมี data URI เพียงครั้งเดียว: `data:image/png;base64,`
- ห้ามเกิด `data:image/png;base64,data:image/png;base64,`
- QR ต้องเข้ารหัสเลขคูปองของตัวเองตรงกับเลขที่แสดงบนคูปอง
- ห้ามเปลี่ยนเลขคูปองเพื่อแก้ปัญหา QR; ให้แก้ข้อมูล QR ให้ถูกต้องแทน
