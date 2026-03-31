function secureChat() {
    return {
        view: 'login',
        auth: { loggedIn: false, username: null, privateKey: null },
        form: { username: '', password: '' },
        sendForm: {
            receiver: '', message: '', lat: null, lon: null,
            geoLocked: true, burnAfterRead: false,
            file: null, fileName: ''
        },
        sendMode: 'message',
        messages: [],
        unreadCount: 0,
        currentMessage: null,
        currentMessageId: null,
        decrypted: null,
        decryptedFileBlob: null,
        error: '',
        info: '',
        loading: false,
        sending: false,
        decrypting: false,
        map: null,
        currentTime: '',

        async init() {
            this.updateClock();
            setInterval(() => this.updateClock(), 1000);
            document.addEventListener('visibilitychange', () => {
                if (document.hidden && this.decrypted && this.view === 'view') {
                    this.decrypted = null;
                    this.decryptedFileBlob = null;
                }
            });
            document.addEventListener('contextmenu', (e) => {
                if (e.target.closest('.no-select')) e.preventDefault();
            });
        },

        updateClock() {
            const now = new Date();
            this.currentTime = now.toLocaleTimeString('en-GB', { hour12: false });
        },

        navigate(v) {
            this.error = '';
            this.info = '';
            this.decrypted = null;
            this.decryptedFileBlob = null;
            this.view = v;
            if (v === 'inbox') this.loadInbox();
            if (v === 'send') this.$nextTick(() => this.initMap());
        },

        async derivePrivateKey(username, password) {
            const salt = new TextEncoder().encode(username + ':securechat:v1');
            const keyMaterial = await crypto.subtle.importKey(
                'raw', new TextEncoder().encode(password), 'PBKDF2', false, ['deriveBits']
            );
            const bits = await crypto.subtle.deriveBits(
                { name: 'PBKDF2', salt, iterations: 310000, hash: 'SHA-256' },
                keyMaterial, 256
            );
            const seed = Array.from(new Uint8Array(bits)).map(b => String.fromCharCode(b)).join('');
            const prng = forge.random.createInstance();
            prng.seedFileSync = () => seed;
            return new Promise((resolve, reject) => {
                forge.pki.rsa.generateKeyPair({ bits: 2048, prng }, (err, keypair) => {
                    if (err) reject(err);
                    else resolve(keypair);
                });
            });
        },

        async register() {
            this.error = '';
            this.loading = true;
            this.info = 'generating rsa-2048 keypair...';
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
                if (!res.ok) { this.error = data.error; this.info = ''; this.loading = false; return; }
                this.info = 'registered. redirecting to login...';
                setTimeout(() => { this.navigate('login'); this.loading = false; }, 1500);
            } catch (e) {
                this.error = e.message;
                this.info = '';
                this.loading = false;
            }
        },

        async login() {
            this.error = '';
            this.loading = true;
            try {
                const keypair = await this.derivePrivateKey(this.form.username, this.form.password);
                const res = await fetch('/api/auth/login', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ username: this.form.username, password: this.form.password })
                });
                const data = await res.json();
                if (!res.ok) { this.error = data.error || 'invalid credentials'; this.loading = false; return; }
                this.auth = { loggedIn: true, username: data.username, privateKey: keypair.privateKey };
                this.loading = false;
                this.navigate('inbox');
            } catch (e) {
                this.error = e.message;
                this.loading = false;
            }
        },

        async logout() {
            await fetch('/api/auth/logout', { method: 'POST' });
            this.auth = { loggedIn: false, username: null, privateKey: null };
            this.view = 'login';
            this.messages = [];
            this.unreadCount = 0;
        },

        async loadInbox() {
            try {
                const res = await fetch('/api/messages/inbox');
                if (res.ok) {
                    const data = await res.json();
                    this.messages = data.messages || [];
                    this.unreadCount = data.unreadCount || 0;
                }
            } catch (e) { this.error = 'failed to load inbox'; }
        },

        initMap() {
            if (this.map) { this.map.remove(); this.map = null; }
            const mapEl = document.getElementById('map');
            if (!mapEl) return;
            this.map = L.map('map').setView([20.5937, 78.9629], 5);
            L.tileLayer('https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png', {
                attribution: '&copy; CartoDB',
                maxZoom: 19
            }).addTo(this.map);
            let marker = null;
            this.map.on('click', (e) => {
                if (marker) marker.remove();
                marker = L.marker(e.latlng).addTo(this.map);
                this.sendForm.lat = e.latlng.lat;
                this.sendForm.lon = e.latlng.lng;
            });
        },

        handleFileUpload(event) {
            const file = event.target.files[0];
            if (file) {
                if (file.size > 10 * 1024 * 1024) {
                    this.error = 'file exceeds 10mb limit';
                    return;
                }
                this.sendForm.file = file;
                this.sendForm.fileName = file.name;
            }
        },

        generateNoiseImage() {
            const canvas = document.createElement('canvas');
            canvas.width = 512;
            canvas.height = 512;
            const ctx = canvas.getContext('2d');
            const imageData = ctx.createImageData(512, 512);
            const data = imageData.data;
            for (let i = 0; i < data.length; i += 4) {
                data[i] = Math.floor(Math.random() * 256);
                data[i + 1] = Math.floor(Math.random() * 256);
                data[i + 2] = Math.floor(Math.random() * 256);
                data[i + 3] = 255;
            }
            ctx.putImageData(imageData, 0, 0);
            return new Promise(resolve => canvas.toBlob(resolve, 'image/png'));
        },

        async sendMessage() {
            this.error = '';
            this.info = '';
            if (this.sendForm.geoLocked && !this.sendForm.lat) {
                this.error = 'click on the map to set geo-lock location';
                return;
            }
            if (this.sendMode === 'message' && !this.sendForm.message) {
                this.error = 'message cannot be empty';
                return;
            }
            if (this.sendMode === 'document' && !this.sendForm.file) {
                this.error = 'select a file to send';
                return;
            }
            if (!this.sendForm.receiver) {
                this.error = 'enter a receiver username';
                return;
            }

            this.sending = true;
            try {
                const keyRes = await fetch('/api/users/' + this.sendForm.receiver);
                if (!keyRes.ok) { this.error = 'receiver not found'; this.sending = false; return; }
                const { publicKey: pubKeyPem } = await keyRes.json();
                const receiverPublicKey = forge.pki.publicKeyFromPem(pubKeyPem);

                const aesKey = new Uint8Array(32);
                crypto.getRandomValues(aesKey);

                const shard1 = aesKey.slice(0, 16);
                const shard2 = aesKey.slice(16, 32);
                const shard2Base64 = btoa(String.fromCharCode(...shard2));

                let payloadBytes;
                if (this.sendMode === 'message') {
                    payloadBytes = new TextEncoder().encode(this.sendForm.message);
                } else {
                    payloadBytes = new Uint8Array(await this.sendForm.file.arrayBuffer());
                }

                const iv = new Uint8Array(12);
                crypto.getRandomValues(iv);
                const importedKey = await crypto.subtle.importKey('raw', aesKey, 'AES-GCM', false, ['encrypt']);
                const ciphertext = await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, importedKey, payloadBytes);

                const payload = new Uint8Array(12 + ciphertext.byteLength);
                payload.set(iv, 0);
                payload.set(new Uint8Array(ciphertext), 12);
                const payloadBase64 = btoa(String.fromCharCode(...payload));

                const shard1Bytes = forge.util.createBuffer(String.fromCharCode(...shard1));
                const encryptedShard1 = receiverPublicKey.encrypt(shard1Bytes.data, 'RSA-OAEP', {
                    md: forge.md.sha256.create()
                });
                const encryptedKeyBase64 = btoa(encryptedShard1);

                const formData = new FormData();
                formData.append('encryptedPayload', payloadBase64);
                formData.append('encryptedKey', encryptedKeyBase64);
                formData.append('keyShard', shard2Base64);
                formData.append('receiverUsername', this.sendForm.receiver);
                formData.append('geoLocked', this.sendForm.geoLocked);
                formData.append('burnAfterRead', this.sendForm.burnAfterRead);

                if (this.sendForm.geoLocked) {
                    formData.append('lat', this.sendForm.lat);
                    formData.append('lon', this.sendForm.lon);
                }

                if (this.sendMode === 'document') {
                    formData.append('file', this.sendForm.file);
                } else {
                    const noiseBlob = await this.generateNoiseImage();
                    formData.append('image', noiseBlob, 'noise.png');
                }

                const res = await fetch('/api/messages/send', { method: 'POST', body: formData });
                const data = await res.json();
                if (!res.ok) { this.error = data.error || 'send failed'; this.sending = false; return; }
                this.info = 'message transmitted successfully';
                this.sendForm = { receiver: '', message: '', lat: null, lon: null, geoLocked: true, burnAfterRead: false, file: null, fileName: '' };
            } catch (e) {
                this.error = 'error: ' + e.message;
            }
            this.sending = false;
        },

        async openMessage(msg) {
            if (msg.expired) return;
            this.currentMessage = msg;
            this.currentMessageId = msg.id;
            this.decrypted = null;
            this.decryptedFileBlob = null;
            this.error = '';
            this.view = 'view';
        },

        async decryptMessage() {
            this.error = '';
            if (!this.auth.privateKey) { this.error = 'session expired. login again.'; return; }
            this.decrypting = true;

            try {
                let shard2;

                const msgRes = await fetch('/api/messages/' + this.currentMessageId);
                if (!msgRes.ok) { this.error = 'failed to load message'; this.decrypting = false; return; }
                const msg = await msgRes.json();

                if (msg.expired) { this.error = 'message has expired'; this.decrypting = false; return; }

                if (msg.geoLocked) {
                    const pos = await new Promise((resolve, reject) =>
                        navigator.geolocation.getCurrentPosition(resolve, reject, {
                            enableHighAccuracy: true,
                            timeout: 15000,
                            maximumAge: 0
                        })
                    );
                    const { latitude: lat, longitude: lon } = pos.coords;

                    const verifyRes = await fetch('/api/location/verify', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ lat, lon, messageId: this.currentMessageId })
                    });
                    const verifyData = await verifyRes.json();

                    if (!verifyData.valid) {
                        this.error = verifyData.message || 'location verification failed. you are ' + verifyData.distance + 'm away, need to be within ' + verifyData.radius + 'm';
                        this.decrypting = false;
                        return;
                    }
                    shard2 = verifyData.keyShard;
                } else {
                    shard2 = msg.keyShard;
                }

                if (!shard2) {
                    this.error = 'key shard unavailable. message may have expired.';
                    this.decrypting = false;
                    return;
                }

                const encryptedShard1Bytes = atob(msg.encryptedKey);
                const shard1Bytes = this.auth.privateKey.decrypt(encryptedShard1Bytes, 'RSA-OAEP', {
                    md: forge.md.sha256.create()
                });
                const shard1 = new Uint8Array(shard1Bytes.split('').map(c => c.charCodeAt(0)));

                const shard2Decoded = new Uint8Array(atob(shard2).split('').map(c => c.charCodeAt(0)));

                const aesKey = new Uint8Array(32);
                aesKey.set(shard1, 0);
                aesKey.set(shard2Decoded, 16);

                if (msg.isDocument) {
                    const fileRes = await fetch('/api/messages/' + this.currentMessageId + '/file');
                    const encryptedBlob = await fileRes.blob();
                    const encryptedBytes = new Uint8Array(await encryptedBlob.arrayBuffer());

                    const iv = encryptedBytes.slice(0, 12);
                    const ciphertext = encryptedBytes.slice(12);
                    const importedKey = await crypto.subtle.importKey('raw', aesKey, 'AES-GCM', false, ['decrypt']);
                    const plaintext = await crypto.subtle.decrypt({ name: 'AES-GCM', iv }, importedKey, ciphertext);
                    this.decryptedFileBlob = new Blob([plaintext]);
                    this.decrypted = 'document ready';
                } else {
                    const imgRes = await fetch('/api/messages/' + this.currentMessageId + '/image');
                    const imgBlob = await imgRes.blob();
                    const extractedPayload = await extractLsbPayload(imgBlob);

                    const iv = extractedPayload.slice(0, 12);
                    const ciphertext = extractedPayload.slice(12);
                    const importedKey = await crypto.subtle.importKey('raw', aesKey, 'AES-GCM', false, ['decrypt']);
                    const plaintext = await crypto.subtle.decrypt({ name: 'AES-GCM', iv }, importedKey, ciphertext);
                    this.decrypted = new TextDecoder().decode(plaintext);
                }
            } catch (e) {
                this.error = 'decryption failed: ' + e.message;
            }
            this.decrypting = false;
        },

        downloadDecryptedFile() {
            if (!this.decryptedFileBlob || !this.currentMessage) return;
            const url = URL.createObjectURL(this.decryptedFileBlob);
            const a = document.createElement('a');
            a.href = url;
            a.download = this.currentMessage.fileName || 'document';
            a.click();
            URL.revokeObjectURL(url);
        },

        formatTime(iso) {
            if (!iso) return '';
            const d = new Date(iso);
            return d.toLocaleString('en-GB', { day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit', hour12: false });
        },

        getCountdown(expiresAt) {
            if (!expiresAt) return '';
            const remaining = new Date(expiresAt) - new Date();
            if (remaining <= 0) return 'expired';
            const mins = Math.floor(remaining / 60000);
            const secs = Math.floor((remaining % 60000) / 1000);
            return mins + 'm ' + secs + 's';
        }
    };
}

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

            let headerBits = '';
            for (let i = 0; i < 32; i++) {
                const pixelIdx = Math.floor(i / 3) * 4;
                const channel = i % 3;
                headerBits += (data[pixelIdx + channel] & 1).toString();
            }
            const payloadLength = parseInt(headerBits, 2);
            if (payloadLength <= 0 || payloadLength > 10_000_000) {
                reject(new Error('invalid payload length: ' + payloadLength));
                return;
            }

            const totalBits = (4 + payloadLength) * 8;
            const result = new Uint8Array(payloadLength);
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
