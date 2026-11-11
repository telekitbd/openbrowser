# Open Browser v5

VPN অ্যাপ ছাড়া ব্লক সাইট খোলার ব্রাউজার। ভেতরে তিন স্তর আছে:

1. DoH (ISP DNS বাদ) + TLS ফ্র্যাগমেন্টেশন (অটো মোড) — DNS/SNI ব্লকের জন্য
2. **রিলে** (এই ভার্সনের মূল জিনিস) — সরাসরি না খুললে ব্রাউজার নিজেই বিদেশি রিলে সার্ভার দিয়ে সাইট খোলে। IP ব্লকও পার হয়
3. ক্লিন UI: ট্যাব, বুকমার্ক (☆), ডাউনলোড পেজ, হিস্ট্রি। বাইপাস, রিলে, অ্যাড ব্লক সব ব্যাকগ্রাউন্ডে নিজে চলে — UI-তে কোনো অপশন নেই

## রিলে বানানো (একবার করলেই হবে)

KEY আগে থেকেই `relay/worker.js`, `relay/vps-server.js` আর অ্যাপে বসানো আছে। শুধু লিংক লাগবে।

### অপশন ক: Cloudflare Worker (ফ্রি)
1. cloudflare.com-এ ফ্রি অ্যাকাউন্ট খুলুন
2. Workers & Pages → Create → **Hello World** → Deploy
3. **Edit code** → সব মুছে `relay/worker.js`-এর লেখা বসান → Deploy
4. পেজের উপরে `https://something.workers.dev` লিংক পাবেন, সেটা কপি করুন
5. অ্যাপে: অ্যাড্রেস বারে `about:relay` লিখে কীবোর্ডের Go চাপুন → লিংক বসিয়ে সেভ (এই স্ক্রিন মেনুতে দেখা যায় না, একবারই লাগে)

সীমা: ফ্রি প্ল্যানে দিনে ১ লাখ রিকোয়েস্ট। Cloudflare-এর পেছনে থাকা সাইট Worker দিয়ে খোলে না। Cloudflare প্রক্সি-ধরনের ব্যবহারে অ্যাকাউন্ট বন্ধও করতে পারে।

### অপশন ক-২: Deploy বাটন (কপি-পেস্ট ছাড়া)
GitHub repo public করে এই লিংক খুলুন (USERNAME বদলে নিন):
`https://deploy.workers.cloudflare.com/?url=https://github.com/USERNAME/openbrowser/tree/main/relay`
Cloudflare নিজে Worker বানিয়ে লিংক দেবে।

### অপশন খ: নিজের VPS (সবচেয়ে নির্ভরযোগ্য)
বিদেশি যেকোনো VPS (মাসে ~$4-5), Node.js সহ:
```
npm i ws
KEY=23228a446f85b394e78f2ed9f8bf0864 PORT=8080 node relay/vps-server.js
```
(সবসময় চালু রাখতে `pm2 start relay/vps-server.js`)
অ্যাপে রিলে লিংক: `http://VPS-IP:8080`

## APK বানানো
1. ফোল্ডারটা GitHub repo-তে push করুন (main branch)
2. Actions → "Build APK" চলবে
3. Artifacts থেকে FreeBrowser-APK নামান

ইচ্ছা হলে বিল্ডের আগে `app/src/main/java/com/freebrowser/app/RelayDefaults.kt`-এ `URL` বসিয়ে দিন, তাহলে APK ইনস্টল করেই রিলে চালু থাকবে।
(সতর্কতা: APK যার হাতে যাবে সে-ই আপনার রিলে ব্যবহার করতে পারবে।)

## সমস্যা হলে (টেস্ট স্ক্রিন)
অ্যাড্রেস বারে `about:diag` লিখে Go চাপুন (বা `about:diag example.com`)। প্রায় ১-২ মিনিটে একটা রিপোর্ট আসবে: DNS ঠিক আছে কিনা, IP-তে কানেক্ট হচ্ছে কিনা, কোন বাইপাস পদ্ধতি কাজ করছে, আর রিলে কাজ করছে কিনা। রিপোর্টের স্ক্রিনশট পাঠালে ঠিক কোথায় আটকাচ্ছে বোঝা যাবে।
