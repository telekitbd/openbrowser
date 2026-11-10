# ফ্রি ব্রাউজার (FreeBrowser) v2

VPN ছাড়া ISP-ব্লক সাইট খোলার হালকা Android ব্রাউজার।

- DNS-over-HTTPS (Cloudflare / Google) — ISP DNS ব্যবহার হয় না
- TLS ClientHello ফ্র্যাগমেন্টেশন, **অটো মোড**: কাজ না করলে নিজে অন্য পদ্ধতি চেষ্টা করে, যেটা কাজ করে মনে রাখে
- ট্যাব, প্রাইভেট ট্যাব, বুকমার্ক, হিস্ট্রি, স্পিড-ডায়াল হোম
- অ্যাড ও পপআপ ব্লক
- ঐচ্ছিক আপস্ট্রিম প্রক্সি (HTTP / socks5://) — শুধু সরাসরি না খুললে ব্যবহার হয়

## APK বানানো
1. ফোল্ডারটা GitHub repo-তে push করুন (main branch)
2. Actions ট্যাবে "Build APK" চলবে
3. Artifacts থেকে FreeBrowser-APK নামান (ভেতরে app-release.apk)
