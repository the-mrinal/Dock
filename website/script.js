// Open screenshots in a lightweight lightbox on click.
// Smooth-scroll is handled by CSS (scroll-behavior: smooth on <html>).

(() => {
  const shots = document.querySelectorAll(".shot img");
  if (!shots.length) return;

  const overlay = document.createElement("div");
  overlay.className = "lightbox";
  overlay.setAttribute("role", "dialog");
  overlay.setAttribute("aria-modal", "true");
  overlay.setAttribute("aria-hidden", "true");
  overlay.innerHTML = `
    <button class="lightbox-close" aria-label="Close">&times;</button>
    <img alt="" />
  `;
  document.body.appendChild(overlay);

  const overlayImg = overlay.querySelector("img");
  const closeBtn = overlay.querySelector(".lightbox-close");

  const close = () => {
    overlay.classList.remove("open");
    overlay.setAttribute("aria-hidden", "true");
    document.body.style.overflow = "";
  };
  const open = (src, alt) => {
    overlayImg.src = src;
    overlayImg.alt = alt || "";
    overlay.classList.add("open");
    overlay.setAttribute("aria-hidden", "false");
    document.body.style.overflow = "hidden";
  };

  shots.forEach((img) => {
    img.style.cursor = "zoom-in";
    img.addEventListener("click", () => open(img.src, img.alt));
  });

  overlay.addEventListener("click", (e) => {
    if (e.target === overlay || e.target === closeBtn) close();
  });
  document.addEventListener("keydown", (e) => {
    if (e.key === "Escape" && overlay.classList.contains("open")) close();
  });

  // Inject lightbox styles lazily so style.css stays focused on layout.
  const sheet = document.createElement("style");
  sheet.textContent = `
    .lightbox {
      position: fixed; inset: 0; z-index: 100;
      background: rgba(0, 0, 0, 0.92);
      display: none; align-items: center; justify-content: center;
      padding: 4vh 4vw;
    }
    .lightbox.open { display: flex; }
    .lightbox img {
      max-width: 100%; max-height: 100%;
      border-radius: 10px;
      box-shadow: 0 10px 60px rgba(0, 0, 0, 0.6);
    }
    .lightbox-close {
      position: absolute; top: 18px; right: 22px;
      background: transparent; border: 0;
      color: #fff; font-size: 2rem; line-height: 1;
      cursor: pointer; padding: 8px 12px;
      border-radius: 6px;
    }
    .lightbox-close:hover { background: rgba(255, 255, 255, 0.08); }
    .lightbox-close:focus-visible {
      outline: 2px solid #66d9ff; outline-offset: 2px;
    }
  `;
  document.head.appendChild(sheet);
})();
