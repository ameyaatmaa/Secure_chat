function secureChat() {
    return {
        view: 'login',
        auth: { loggedIn: false, username: null, privateKey: null },
        form: { username: '', password: '' },
        sendForm: { receiver: '', message: '', image: null, lat: null, lon: null },
        messages: [],
        currentMessageId: null,
        decrypted: null,
        error: '',
        info: '',
        sending: false,
        map: null,

        async init() {
            // Check if already logged in (cookie exists)
            try {
                const res = await fetch('/api/messages/inbox');
                if (res.ok) {
                    // Session active — but we don't have private key in memory
                    // User must re-login to derive private key
                }
            } catch(e) {}
        },

        navigate(view) {
            this.error = '';
            this.info = '';
            this.view = view;
            if (view === 'inbox') this.loadInbox();
            if (view === 'send') this.$nextTick(() => this.initMap());
        },

        // --- KEY DERIVATION ---
        async derivePrivateKey(username, password) {
            const salt = new TextEncoder().encode(username + ':securechat:v1');
            const keyMaterial = await crypto.subtle.importKey(
                'raw', new TextEncoder().encode(password),
                'PBKDF2', false, ['deriveBits']
            );
            const bits = await crypto.subtle.deriveBits(
                { name: 'PBKDF2', salt, iterations: 310000, hash: 'SHA-256' },
                keyMaterial, 256
            );
            // Use derived bits as seed for RSA key generation via forge
            const seed = Array.from(new Uint8Array(bits)).map(b => String.fromCharCode(b)).join('');
            const prng = forge.random.createInstance();
            prng.seedFileSync = () => seed;
            return new Promise((resolve, reject) => {
                forge.pki.rsa.generateKeyPair({ bits: 2048, workers: -1, prng }, (err, keypair) => {
                    if (err) reject(err);
                    else resolve(keypair);
                });
            });
        },

        // --- REGISTER ---
        async register() {
            this.error = '';
            this.info = 'Generating cryptographic keys... (this takes a moment)';
            try {
                const keypair = await this.derivePrivateKey(this.form.username, this.form.password);
                const publicKeyPem = forge.pki.publicKeyToPem(keypair.publicKey);
                const res = await fetch('/api/auth/register', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        username: this.form.username,
                        password: this.form.password,
                        publicKey: publicKeyPem
                    })
                });
                const data = await res.json();
                if (!res.ok) { this.error = data.error; this.info = ''; return; }
                this.info = 'Registered! You can now login.';
                setTimeout(() => this.navigate('login'), 1500);
            } catch(e) {
                this.error = e.message;
                this.info = '';
            }
        },

        // --- LOGIN ---
        async login() {
            this.error = '';
            try {
                const keypair = await this.derivePrivateKey(this.form.username, this.form.password);
                const res = await fetch('/api/auth/login', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ username: this.form.username, password: this.form.password })
                });
                const data = await res.json();
                if (!res.ok) { this.error = data.error; return; }
                this.auth = { loggedIn: true, username: data.username, privateKey: keypair.privateKey };
                this.navigate('inbox');
            } catch(e) {
                this.error = e.message;
            }
        },

        // --- LOGOUT ---
        async logout() {
            await fetch('/api/auth/logout', { method: 'POST' });
            this.auth = { loggedIn: false, username: null, privateKey: null };
            this.view = 'login';
        },

        // --- INBOX ---
        async loadInbox() {
            const res = await fetch('/api/messages/inbox');
            if (res.ok) this.messages = await res.json();
        },

        // --- MAP ---
        initMap() {
            if (this.map) { this.map.remove(); this.map = null; }
            this.map = L.map('map').setView([20.5937, 78.9629], 5);
            L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png').addTo(this.map);
            let marker = null;
            this.map.on('click', (e) => {
                if (marker) marker.remove();
                marker = L.marker(e.latlng).addTo(this.map);
                this.sendForm.lat = e.latlng.lat;
                this.sendForm.lon = e.latlng.lng;
            });
        },

        handleImageUpload(event) {
            this.sendForm.image = event.target.files[0];
        },

        // --- SEND MESSAGE ---
        async sendMessage() {
            this.error = '';
            this.info = '';
            if (!this.sendForm.lat) { this.error = 'Please click on the map to set geo-lock location'; return; }
            if (!this.sendForm.image) { this.error = 'Please select an image'; return; }
            if (!this.sendForm.message) { this.error = 'Message cannot be empty'; return; }
            this.sending = true;
            try {
                // Get receiver public key
                const keyRes = await fetch(`/api/users/${this.sendForm.receiver}`);
                if (!keyRes.ok) { this.error = 'Receiver not found'; this.sending = false; return; }
                const { publicKey: pubKeyPem } = await keyRes.json();
                const receiverPublicKey = forge.pki.publicKeyFromPem(pubKeyPem);

                // AES-256-GCM encrypt message
                const aesKey = new Uint8Array(32);
                crypto.getRandomValues(aesKey);
                const iv = new Uint8Array(12);
                crypto.getRandomValues(iv);
                const importedKey = await crypto.subtle.importKey('raw', aesKey, 'AES-GCM', false, ['encrypt']);
                const ciphertext = await crypto.subtle.encrypt(
                    { name: 'AES-GCM', iv },
                    importedKey,
                    new TextEncoder().encode(this.sendForm.message)
                );

                // Bundle payload: iv(12) + ciphertext
                const payload = new Uint8Array(12 + ciphertext.byteLength);
                payload.set(iv, 0);
                payload.set(new Uint8Array(ciphertext), 12);
                const payloadBase64 = btoa(String.fromCharCode(...payload));

                // RSA encrypt AES key with receiver's public key
                const aesKeyBytes = forge.util.createBuffer(String.fromCharCode(...aesKey));
                const encryptedKeyBytes = receiverPublicKey.encrypt(aesKeyBytes.data, 'RSA-OAEP', {
                    md: forge.md.sha256.create()
                });
                const encryptedKeyBase64 = btoa(encryptedKeyBytes);

                // Upload
                const formData = new FormData();
                formData.append('image', this.sendForm.image);
                formData.append('encryptedPayload', payloadBase64);
                formData.append('encryptedKey', encryptedKeyBase64);
                formData.append('receiverUsername', this.sendForm.receiver);
                formData.append('lat', this.sendForm.lat);
                formData.append('lon', this.sendForm.lon);
                formData.append('radius', '50');

                const res = await fetch('/api/messages/send', { method: 'POST', body: formData });
                const data = await res.json();
                if (!res.ok) { this.error = data.error; this.sending = false; return; }
                this.info = 'Message sent successfully!';
                this.sendForm = { receiver: '', message: '', image: null, lat: null, lon: null };
            } catch(e) {
                this.error = 'Error: ' + e.message;
            }
            this.sending = false;
        },

        // --- OPEN + DECRYPT MESSAGE ---
        async openMessage(id) {
            this.currentMessageId = id;
            this.decrypted = null;
            this.error = '';
            this.navigate('view');
        },

        async decryptMessage() {
            this.error = '';
            if (!this.auth.privateKey) { this.error = 'Session expired. Please login again.'; return; }
            try {
                // Get current location
                const pos = await new Promise((resolve, reject) =>
                    navigator.geolocation.getCurrentPosition(resolve, reject)
                );
                const { latitude: lat, longitude: lon } = pos.coords;

                // Coarse server verify
                const verifyRes = await fetch('/api/location/verify', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ lat, lon, messageId: this.currentMessageId })
                });
                const { valid } = await verifyRes.json();
                if (!valid) { this.error = 'Location check failed (server)'; return; }

                // Get message metadata
                const msgRes = await fetch(`/api/messages/${this.currentMessageId}`);
                const msg = await msgRes.json();

                // Client-side fine geo check (Haversine)
                const dist = haversine(msg.senderLat, msg.senderLon, lat, lon);
                if (dist > msg.radiusMeters) {
                    this.error = `You are ${Math.round(dist)}m away. Must be within ${msg.radiusMeters}m.`;
                    return;
                }

                // Download stego image and extract payload
                const imgRes = await fetch(`/api/messages/${this.currentMessageId}/image`);
                const imgBlob = await imgRes.blob();
                const payload = await extractLsbPayload(imgBlob);

                // RSA decrypt AES key
                const encryptedKeyBytes = atob(msg.encryptedKey);
                const aesKeyBytes = this.auth.privateKey.decrypt(encryptedKeyBytes, 'RSA-OAEP', {
                    md: forge.md.sha256.create()
                });
                const aesKey = new Uint8Array(aesKeyBytes.split('').map(c => c.charCodeAt(0)));

                // AES-GCM decrypt
                const iv = payload.slice(0, 12);
                const ciphertext = payload.slice(12);
                const importedKey = await crypto.subtle.importKey('raw', aesKey, 'AES-GCM', false, ['decrypt']);
                const plaintext = await crypto.subtle.decrypt({ name: 'AES-GCM', iv }, importedKey, ciphertext);
                this.decrypted = new TextDecoder().decode(plaintext);

            } catch(e) {
                this.error = 'Decryption failed: ' + e.message;
            }
        }
    };
}

// --- Haversine (client-side fine check) ---
function haversine(lat1, lon1, lat2, lon2) {
    const R = 6371000;
    const dLat = (lat2 - lat1) * Math.PI / 180;
    const dLon = (lon2 - lon1) * Math.PI / 180;
    const a = Math.sin(dLat/2)**2 +
              Math.cos(lat1 * Math.PI/180) * Math.cos(lat2 * Math.PI/180) * Math.sin(dLon/2)**2;
    return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
}

// --- LSB Extraction (client-side) ---
async function extractLsbPayload(imageBlob) {
    return new Promise((resolve, reject) => {
        const img = new Image();
        img.onload = () => {
            const canvas = document.createElement('canvas');
            canvas.width = img.width;
            canvas.height = img.height;
            const ctx = canvas.getContext('2d');
            ctx.drawImage(img, 0, 0);
            const data = ctx.getImageData(0, 0, img.width, img.height).data;

            // Read header (first 32 bits = payload length)
            let headerBits = '';
            for (let i = 0; i < 32; i++) {
                const pixelIdx = Math.floor(i / 3) * 4;
                const channel = i % 3;
                headerBits += (data[pixelIdx + channel] & 1).toString();
            }
            const payloadLength = parseInt(headerBits, 2);
            if (payloadLength <= 0 || payloadLength > 10_000_000) {
                reject(new Error('Invalid payload length: ' + payloadLength));
                return;
            }

            // Read payload bits
            const totalBits = (4 + payloadLength) * 8;
            const result = new Uint8Array(payloadLength);
            let bitIdx = 32; // skip header
            let resultBit = 0;
            for (let i = 32; i < totalBits && resultBit < payloadLength * 8; i++) {
                const pixelIdx = Math.floor(i / 3) * 4;
                const channel = i % 3;
                const bit = data[pixelIdx + channel] & 1;
                if (bit) result[Math.floor(resultBit / 8)] |= (1 << (7 - (resultBit % 8)));
                resultBit++;
            }
            resolve(result);
        };
        img.onerror = reject;
        img.src = URL.createObjectURL(imageBlob);
    });
}
