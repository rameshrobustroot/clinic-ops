// Register the service worker (PWA shell)
if ('serviceWorker' in navigator) {
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('/sw.js').catch((e) => console.warn('SW registration failed', e));
  });
}

// Helper: open a WhatsApp deep link with a prefilled message.
// Stored numbers are bare 10-digit Indian mobiles; wa.me needs a country code,
// so we auto-prepend 91 here. No persistence side-effect.
function whatsapp(phone, message) {
  let cleaned = (phone || '').replace(/[^0-9]/g, '');
  if (!cleaned) {
    alert('Phone number missing');
    return;
  }
  if (cleaned.length === 10) {
    cleaned = '91' + cleaned;
  }
  const text = encodeURIComponent(message || '');
  window.open(`https://wa.me/${cleaned}?text=${text}`, '_blank');
}
window.whatsapp = whatsapp;

// Delegate clicks on any element with .whatsapp-btn
document.addEventListener('click', (e) => {
  const btn = e.target.closest('.whatsapp-btn');
  if (!btn) return;
  whatsapp(btn.dataset.phone, btn.dataset.message);
});
