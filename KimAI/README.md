# Kim AI

Kim AI is a Thai-first personal AI workspace, published as a web app inside this repository.

## Open the app

After GitHub Pages publishes the latest commit:

https://walking2129.github.io/Teenoi-Coupon/KimAI/

The app files are isolated in `KimAI/`; existing coupon and POS folders are not modified.

## Included in this first release

- Responsive ChatGPT-style chat interface for mobile and desktop
- Local chat history and chat search
- Chat, Agent-planning, and Code modes
- Text-file attachment (up to 250 KB)
- Thai speech-to-text on supported browsers
- Copy assistant responses and stop generation
- Server-side API bridge in `api/`

## Enable actual AI replies

GitHub Pages serves static files only. It cannot run the API functions in this folder. Deploy `KimAI/` as a project on Vercel (Root Directory: `KimAI`) or another Node-compatible serverless host.

Configure these environment variables on the backend host:

- `OPENAI_API_KEY` — secret provider API key. Never put it in browser JavaScript or this public repository.
- `KIM_AI_ACCESS_CODE` — a long random password used by the app to call your backend.
- `OPENAI_MODEL` — optional model name enabled for your API project. Defaults to `gpt-5`.
- `ALLOWED_ORIGIN` — optional allowed front-end origin. Defaults to `https://walking2129.github.io`.

Then open Kim AI, choose Settings, and enter:

1. Backend URL (the Vercel deployment URL, not the GitHub Pages URL)
2. The same `KIM_AI_ACCESS_CODE`
3. An API model that your provider account can use

Press Test connection. The app stores the backend URL and model in local browser storage. The access code is held in session storage for the current tab only. The provider API key remains on the backend.

## Important status and boundaries

This is the first working UI and API bridge, not a full autonomous Manus replacement yet. Agent mode currently provides task planning; it does not control a live browser or computer. Makro Pro purchasing, POS operations, persistent cloud memory, multi-agent orchestration, file-system access, email, and other external actions need separate integrations and permissioned workers.

No software can remove the underlying model's usage limits, provider policies, applicable laws, or access controls on external websites. API usage may incur provider charges. External purchases and payments should require explicit user confirmation.

## Local development

Use Node.js with Vercel CLI and set the project root to this folder, or run the API handlers on another compatible serverless platform.
