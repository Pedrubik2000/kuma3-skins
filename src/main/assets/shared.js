// kuma3 Skins: what every page shares (loaded before the page's own script, which defines st, card, refresh,
// reveal…). The card code: each skin's cardDoc() builds the card page, showCard() shows it.
// window.Anki (the app's bridge) is in every frame, the card's too, so its calls carry a token only this page gets:
// asked here, before any card exists. B.name(args) calls the bridge's method name.
const B = (() => {
  const A = window.Anki; if (!A) return null;
  const t = A.token();
  return new Proxy({}, {get: (_, name) => (...a) => A.call(t, name, JSON.stringify(a)) ?? undefined});
})();

// The card frame is its own page (Anki.setCard writes it, st.cardUrl), cross-origin to this one, so a shared deck's
// scripts can't reach this page or kuma3 (the browser preview, without the bridge, uses srcdoc). The two only talk by
// messages: cardHelper runs inside the card.
function showCard(frame, html) {
  editButton(frame);
  if (!st.cardUrl) return frame.srcdoc = html;
  B.setCard(html);
  frame.src = `${st.cardUrl}?${Date.now()}`;
}
// ✎ 🗑 ⋯ in the card's corner: kuma3's note editor on this card (Back returns here, and the card is fetched again);
// delete the note after the app's own confirmation; ⋯ = flag, bury, suspend. A card that leaves (deleted, buried,
// suspended) goes through cardGone(): the skin's cardDeleted() or refresh() shows the next one.
const FLAGS = ["", "#e53935", "#fb8c00", "#43a047", "#1e88e5", "#ec407a", "#26a69a", "#8e24aa"];   // 1 red … 7 purple
function cardGone() { window.cardDeleted ? cardDeleted() : refresh(); }
function editButton(frame) {
  const box = frame.parentElement; if (!box || !card) return;
  if (getComputedStyle(box).position === "static") box.style.position = "relative";
  box.querySelector(".cardtools")?.remove();
  const tools = document.createElement("div");
  tools.className = "cardtools";
  tools.style.cssText = "position:absolute;top:6px;right:6px;z-index:3;display:flex;gap:8px;align-items:flex-start";
  const round = "width:40px;height:40px;border-radius:50%;border:0;padding:0;background:#0007;color:#fff;font-size:20px;line-height:40px;cursor:pointer";
  const button = (icon, title, act) => {
    const b = document.createElement("button");
    b.textContent = icon; b.title = title; b.onclick = act; b.style.cssText = round;
    tools.append(b);
    return b;
  };
  const demo = what => alert(`In the app this ${what}.`);
  const more = button("⋯", "Flag, bury, suspend", () => {
    if (tools.querySelector(".cardmenu")) return tools.querySelector(".cardmenu").remove();
    const m = document.createElement("div");
    m.className = "cardmenu";
    m.style.cssText = "position:absolute;top:46px;right:0;min-width:220px;padding:8px;border-radius:12px;background:#222e;color:#fff;font:16px system-ui,sans-serif;box-shadow:0 4px 12px #0008";
    const dots = FLAGS.map((c, f) => `<button data-f="${f}" title="${f ? "Flag " + f : "No flag"}" style="width:24px;height:24px;border-radius:50%;padding:0;cursor:pointer;
      border:2px solid ${(card.flag | 0) === f ? "#fff" : "transparent"};background:${c || "#555"};color:#fff;font-size:13px">${f ? "" : "✕"}</button>`).join("");
    const row = (id, label) => `<button data-a="${id}" style="display:block;width:100%;padding:10px 4px;border:0;background:none;color:inherit;font:inherit;text-align:left;cursor:pointer">${label}</button>`;
    m.innerHTML = `<div style="display:flex;gap:6px;flex-wrap:wrap;padding:4px 4px 8px">${dots}</div>${row("bury", "Bury card (until tomorrow)")}${row("suspend", "Suspend card")}`;
    m.querySelectorAll("[data-f]").forEach(b => b.onclick = () => {
      const f = +b.dataset.f;
      if (!B) return demo(f ? `flags the card ${f}` : "clears the flag");
      try { B.flag(card.nid, card.ord, f); card.flag = f; editButton(frame); } catch (e) {}
    });
    m.querySelector('[data-a="bury"]').onclick = () => { if (!B) return demo("buries the card"); try { B.bury(card.nid, card.ord); } catch (e) {} cardGone(); };
    m.querySelector('[data-a="suspend"]').onclick = () => { if (!B) return demo("suspends the card"); try { B.suspend(card.nid, card.ord); } catch (e) {} cardGone(); };
    tools.append(m);
  });
  if (card.flag) more.style.boxShadow = `inset 0 0 0 3px ${FLAGS[card.flag]}`;   // the card's flag on ⋯
  button("🗑", "Delete this note", () => B ? B.deleteNote(card.nid) : demo("asks, then deletes the note"));
  button("✎", "Edit this card in kuma3", () => B ? B.editNote(card.nid, card.ord) : demo("opens kuma3's note editor on this card"));
  tools.append(more);   // ⋯ last, its menu opens under it
  box.append(tools);
}
const cardFrame = () => document.getElementById("face");
const toCard = m => cardFrame()?.contentWindow?.postMessage(m, "*");
addEventListener("message", e => {
  if (!e.source || e.source !== cardFrame()?.contentWindow) return;
  const m = e.data || {};
  // what each skin has: the combo clock (wordDone), the type box (typed), the music that ducks (setVol)
  if (m.t === "word" && typeof wordDone === "function") wordDone(m.tok);
  else if (m.t === "focus") cardFrame().focus();
  else if (m.t === "typed" && typeof typed !== "undefined") typed = String(m.v);
  else if (m.t === "enter") reveal();
  // a card that grades itself (multiple choice: right = 3, wrong = 1): show the answer, then rate it like the buttons
  else if (m.t === "rate" && [1, 2, 3, 4].includes(m.ease)) { reveal(); rate(m.ease); }
  else if (m.t === "media" && typeof setVol === "function") { ducked = !!m.on; setVol(); }
});
function cardHelper() {
  const up = m => parent.postMessage(m, "*");
  // L1: the card's word audio, then its sentence audio, then its clip, in that order (user). The 🐻 German template
  // keeps them as [audio:] tags in [data-audio="word"] and [data-audio="sentence"] (the clip = the .webm/.mp4 there)
  // and its <video> as window.__v. Other cards: their first replay button, audio or video. L1 again restarts.
  let replayTok = 0, replayCur = null;
  function replay() {
    const w = window, d = document;
    const files = sel => [...(d.querySelector(sel)?.textContent || "").matchAll(/\[(?:audio|sound):\s*([^\]]+?)\s*\]/g)].map(m => m[1]);
    const sent = files('[data-audio="sentence"]'), clip = sent.find(f => /\.(webm|mp4)$/i.test(f));
    const list = [...files('[data-audio="word"]'), ...sent.filter(f => f !== clip)];
    const video = w.__v || d.querySelector("video");
    if (!list.length && !clip) {
      const b = d.querySelector(".replay-button"), m = d.querySelector("audio,video");
      if (b) b.click(); else if (m) { m.currentTime = 0; m.play(); }
      return;
    }
    const tok = ++replayTok;
    const stopAll = () => { replayCur?.pause(); video?.pause(); d.querySelectorAll("audio,video").forEach(m => m.pause()); };
    const play = m => new Promise(done => {   // resolves when it ends, fails or L1 starts over
      if (tok !== replayTok) return;
      stopAll(); replayCur = m;
      m.onended = m.onerror = () => done();
      try { m.currentTime = 0; } catch (e) {}
      m.play()?.catch(() => done());
    });
    stopAll();
    // the card's own player when it has one (it also stops the card's autoplay), else a plain Audio
    const say = f => w.__playMobile ? w.__playMobile(f) : play(new w.Audio(f));
    list.reduce((p, f) => p.then(() => tok === replayTok && say(f)), Promise.resolve())
      .then(() => tok === replayTok && video && play(video));
  }
  addEventListener("message", e => {
    if (e.source !== parent) return;
    if (e.data?.t === "replay") replay();
    if (e.data?.t === "scroll") scrollBy({top: e.data.dy, behavior: "smooth"});
  });
  // its audio/video playing (the app's music ducks), its type-the-answer box (keyboard up, typing kept, Enter = answer)
  const media = () => up({t: "media", on: [...document.querySelectorAll("audio,video")].some(m => !m.paused && !m.ended)});
  ["play", "playing", "pause", "ended"].forEach(ev => document.addEventListener(ev, media, true));
  addEventListener("load", () => {
    media();
    const i = document.getElementById("typeans"); if (!i) return;
    up({t: "focus"}); i.focus();
    i.oninput = () => up({t: "typed", v: i.value});
    i.onkeydown = e => { if (e.key === "Enter") { e.preventDefault(); up({t: "enter"}); } };
  });
}
