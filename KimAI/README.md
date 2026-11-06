# Kim AI

Kim AI is a Thai-first personal AI workspace, published as a web app inside this repository.

## Open the app

https://walking2129.github.io/Teenoi-Coupon/KimAI/

The app files are isolated in `KimAI/`; existing coupon and POS folders are not modified.

## Current AI provider: Google Gemini

The backend in `api/` calls the Gemini Developer API. The default model is `gemini-3.8-flash`.

1. Create a Gemini API key in [Google AI Studio](https://aistudio.google.com/app/apikey).
2. Deploy this `KimAI/` folder as a Vercel project (Root Directory: `KimAI`).
3. In Vercel Project Settings → Environment Variables, add:
   - `GEMINI_API_KEY`: your secret key from Google AI Studio
   - `KIM_AI_ACCESS_CODE`: a long, unique access password for Kim AI
   - `GEMINI_MODEL`: optional; defaults to `gemini-3.8-flash`
   - `ALLOWED_ORIGIN`: optional; defaults to `https://walking2129.github.io`
4. Redeploy the Vercel project after adding or changing environment variables.
5. In Kim AI Settings, enter the Vercel deployment URL and the same `KIM_AI_ACCESS_CODE`. Keep the model as `gemini-3.8-flash`, then press Test connection.

**Never paste the Gemini API key into the browser, chat, or this public GitHub repository.** The key belongs only in the backend host's encrypted environment variables.

## Free-tier notes

Google offers a no-cost Gemini API tier for selected models, subject to model-specific rate limits and availability. Check the current limits in Google AI Studio. Free-tier data handling terms may differ from paid-tier terms. Do not assume unlimited use.

## Included

- Responsive Thai-first chat UI
- Local chat history and search
- Chat, Agent-planning, and Code modes
- Text-file attachment (up to 250 KB)
- Thai speech-to-text on supported browsers
- Server-side Gemini API bridge

Agent mode currently plans tasks; it does not control a live browser or computer. Purchasing, POS operations, cloud memory, and external actions need separate integrations and explicit permissions. External purchases and payments must require user confirmation.

## Local development

Use Node.js with Vercel CLI and set the project root to this folder, or run the API handlers on another compatible serverless platform.
