(() => {
  const dialog = document.getElementById('diagram-dialog');
  const content = document.getElementById('dialog-content');
  let width = 1000;
  function resize() { const svg = content.querySelector('svg'); if (svg) svg.style.width = width + 'px'; }
  for (const button of document.querySelectorAll('.expand-diagram')) button.addEventListener('click', () => {
    const original = document.querySelector(`[data-diagram="${button.dataset.id}"] svg`);
    content.replaceChildren(original.cloneNode(true));
    document.getElementById('dialog-title').textContent = original.getAttribute('aria-label');
    const nativeWidth = parseFloat(original.getAttribute('viewBox').split(/\s+/)[2]);
    width = Math.max(760, Math.min(2000, nativeWidth * 1.25));
    resize(); dialog.showModal(); content.scrollTo(0,0);
  });
  document.getElementById('dialog-close').addEventListener('click', () => dialog.close());
  document.getElementById('zoom-in').addEventListener('click', () => { width = Math.min(6000, width * 1.25); resize(); });
  document.getElementById('zoom-out').addEventListener('click', () => { width = Math.max(400, width / 1.25); resize(); });
  document.getElementById('print').addEventListener('click', () => window.print());
  const links = [...document.querySelectorAll('nav a')];
  const observer = new IntersectionObserver(entries => {
    const current = entries.filter(e => e.isIntersecting).sort((a,b) => a.boundingClientRect.top - b.boundingClientRect.top)[0];
    if (!current) return;
    for (const link of links) link.classList.toggle('active', link.hash === '#' + current.target.id);
  }, { rootMargin:'-5% 0px -70% 0px' });
  document.querySelectorAll('h2[id]').forEach(h => observer.observe(h));
})();
