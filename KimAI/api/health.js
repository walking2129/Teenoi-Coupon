module.exports = async function handler(req, res) {
  const allowedOrigin = process.env.ALLOWED_ORIGIN || "https://walking2129.github.io";
  const origin = req.headers.origin;
  res.setHeader("Access-Control-Allow-Origin", allowedOrigin);
  res.setHeader("Vary", "Origin");
  res.setHeader("Access-Control-Allow-Headers", "Authorization, Content-Type");
  res.setHeader("Access-Control-Allow-Methods", "GET, OPTIONS");
  if (origin && origin !== allowedOrigin) return res.status(403).json({ ok: false, error: "Origin not allowed" });
  if (req.method === "OPTIONS") return res.status(204).end();
  if (req.method !== "GET") return res.status(405).json({ ok: false, error: "Method not allowed" });
  const accessCode = process.env.KIM_AI_ACCESS_CODE;
  if (!accessCode || req.headers.authorization !== "Bearer " + accessCode) {
    return res.status(401).json({ ok: false, error: "รหัสเข้าใช้งานไม่ถูกต้อง หรือ Backend ยังไม่ได้ตั้งค่า KIM_AI_ACCESS_CODE" });
  }
  if (!process.env.GEMINI_API_KEY) {
    return res.status(503).json({ ok: false, error: "Backend ยังไม่ได้ตั้งค่า GEMINI_API_KEY" });
  }
  return res.status(200).json({ ok: true, provider: "Google Gemini", model: process.env.GEMINI_MODEL || "gemini-2.5-flash" });
};
