(function () {
  const $ = (id) => document.getElementById(id);
  const input = $("input");
  const resize = () => {
    input.style.height = "auto";
    input.style.height = Math.min(input.scrollHeight, 170) + "px";
  };

  document.querySelectorAll("[data-prompt]").forEach((button) => {
    button.addEventListener("click", () => {
      input.value = button.getAttribute("data-prompt") || "";
      resize();
      input.focus();
    });
  });

  $("mode").addEventListener("change", () => {
    const descriptions = {
      chat: "โหมดสนทนาทั่วไป",
      agent: "ช่วยวางแผนและแตกงานเป็นขั้นตอน",
      code: "ช่วยเขียนและตรวจโค้ด"
    };
    $("modehint").textContent = descriptions[$("mode").value] || descriptions.chat;
  });

  $("attach").addEventListener("click", () => $("file").click());
  $("file").addEventListener("change", async () => {
    const file = $("file").files && $("file").files[0];
    if (!file) return;
    if (file.size > 250000) {
      alert("ไฟล์ข้อความต้องมีขนาดไม่เกิน 250 KB");
      $("file").value = "";
      return;
    }
    try {
      const content = await file.text();
      input.value = (input.value ? input.value + "\n\n" : "") +
        "ช่วยอ่านและวิเคราะห์ไฟล์ " + file.name + "\n\n" + content;
      $("attached").textContent = "แนบแล้ว: " + file.name;
      resize();
      input.focus();
    } catch (error) {
      alert("อ่านไฟล์ไม่สำเร็จ");
    }
    $("file").value = "";
  });

  $("voice").addEventListener("click", () => {
    const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
    if (!SpeechRecognition) {
      alert("เบราว์เซอร์นี้ไม่รองรับการป้อนเสียง ลองใช้ Chrome บน Android");
      return;
    }
    const recognition = new SpeechRecognition();
    recognition.lang = "th-TH";
    recognition.interimResults = false;
    recognition.onresult = (event) => {
      input.value = (input.value ? input.value + " " : "") + event.results[0][0].transcript;
      resize();
      input.focus();
    };
    recognition.onerror = () => alert("รับเสียงไม่สำเร็จ กรุณาลองใหม่");
    recognition.start();
  });
})();