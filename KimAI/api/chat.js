module.exports = async function handler(req, res) {
  const allowedOrigin = process.env.ALLOWED_ORIGIN || "https://walking2129.github.io";
  const origin = req.headers.origin;
  res.setHeader("Access-Control-Allow-Origin", allowedOrigin);
  res.setHeader("Vary", "Origin");
  res.setHeader("Access-Control-Allow-Headers", "Authorization, Content-Type");
  res.setHeader("Access-Control-Allow-Methods", "POST, OPTIONS");
  if (origin && origin !== allowedOrigin) return res.status(403).json({ error: "Origin not allowed" });
  if (req.method === "OPTIONS") return res.status(204).end();
  if (req.method !== "POST") return res.status(405).json({ error: "Method not allowed" });

  const accessCode = process.env.KIM_AI_ACCESS_CODE;
  if (!accessCode || req.headers.authorization !== "Bearer " + accessCode) {
    return res.status(401).json({ error: "รหัสเข้าใช้งานไม่ถูกต้อง หรือ Backend ยังไม่ได้ตั้งค่า KIM_AI_ACCESS_CODE" });
  }
  const apiKey = process.env.GEMINI_API_KEY;
  if (!apiKey) return res.status(503).json({ error: "Backend ยังไม่ได้ตั้งค่า GEMINI_API_KEY" });

  let body = req.body || {};
  if (typeof body === "string") {
    try { body = JSON.parse(body); } catch (_) { return res.status(400).json({ error: "Invalid JSON body" }); }
  }
  const allowedModes = ["chat", "agent", "code"];
  const mode = allowedModes.includes(body.mode) ? body.mode : "chat";
  if (!Array.isArray(body.messages)) return res.status(400).json({ error: "Messages must be an array" });

  const contents = [];
  let total = 0;
  for (const item of body.messages.slice(-24)) {
    if (!item || !["user", "assistant"].includes(item.role) || typeof item.content !== "string") continue;
    total += item.content.length;
    if (item.content.length > 30000 || total > 80000) return res.status(413).json({ error: "ข้อความยาวเกินขนาดที่อนุญาต" });
    contents.push({ role: item.role === "assistant" ? "model" : "user", parts: [{ text: item.content }] });
  }
  if (!contents.some((item) => item.role === "user")) return res.status(400).json({ error: "กรุณาส่งข้อความก่อน" });

  let modeInstruction = "สนทนาเป็นภาษาไทยอย่างเป็นธรรมชาติ ตอบตรงประเด็น และบอกให้ชัดเมื่อไม่แน่ใจ";
  if (mode === "agent") modeInstruction = "ช่วยวางแผนงานแบบ AI Agent แตกงานเป็นขั้นตอน ระบุเครื่องมือและสิทธิ์ที่ต้องใช้ ห้ามกล่าวอ้างว่าได้เปิดเว็บหรือทำงานภายนอกแล้ว หากยังไม่มีเครื่องมือดังกล่าวเชื่อมต่อ";
  if (mode === "code") modeInstruction = "เป็นผู้ช่วยเขียนโปรแกรม ให้โค้ดที่ใช้ได้จริง พร้อมวิธีทดสอบและข้อควรระวัง";
  const instructions = "คุณคือ Kim AI ผู้ช่วยส่วนตัวของคิม ใช้ภาษาไทยเป็นหลัก สุภาพและตรงไปตรงมา " +
    modeInstruction +
    " ห้ามกล่าวอ้างว่าเชื่อมต่อเครื่องมือหรือทำธุรกรรมสำเร็จ หากระบบไม่ได้เชื่อมต่อเครื่องมือนั้นจริง";

  const model = String(process.env.GEMINI_MODEL || body.model || "gemini-3.8-flash").trim();
  if (!/^[a-zA-Z0-9._-]+$/.test(model)) return res.status(400).json({ error: "ชื่อโมเดลไม่ถูกต้อง" });
  let upstream;
  try {
    upstream = await fetch("https://generativelanguage.googleapis.com/v1beta/models/" + encodeURIComponent(model) + ":generateContent", {
      method: "POST",
      headers: { "x-goog-api-key": apiKey, "Content-Type": "application/json" },
      body: JSON.stringify({
        systemInstruction: { parts: [{ text: instructions }] },
        contents,
        generationConfig: { temperature: 0.7, maxOutputTokens: 2048 }
      })
    });
  } catch (error) {
    return res.status(502).json({ error: "เชื่อมต่อ Gemini ไม่สำเร็จ: " + error.message });
  }

  const resultText = await upstream.text();
  let result;
  try { result = JSON.parse(resultText); }
  catch (_) { return res.status(502).json({ error: "ได้รับคำตอบที่ไม่ใช่ JSON จาก Gemini" }); }
  if (!upstream.ok) {
    const message = result && result.error && result.error.message ? result.error.message : "Gemini API error";
    return res.status(upstream.status).json({ error: message });
  }

  const output = (result.candidates || []).flatMap((candidate) =>
    (candidate.content && candidate.content.parts || []).filter((part) => typeof part.text === "string").map((part) => part.text)
  ).join("\n");
  return res.status(200).json({ reply: output || "Gemini ไม่ได้ส่งข้อความตอบกลับ", model });
};
