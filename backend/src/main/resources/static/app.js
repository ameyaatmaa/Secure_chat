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
        mapMarker: null,
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
            if (v === 'send') {
                // Destroy old map, wait for DOM, then init fresh
                if (this.map) { this.map.remove(); this.map = null; }
                setTimeout(() => this.initMap(), 500);
            }
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
                forge.pki.rsa.generateKeyPair({ bits: 2048, workers: 0, prng }, (err, keypair) => {
                    if (err) reject(err);
                    else resolve(keypair);
                });
            });
        },

        async register() {
            this.error = '';
            this.loading = true;
            this.info = 'generating rsa-2048 keypair... (this takes ~15 seconds)';
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
                this.error = e.message || String(e);
                this.info = '';
                this.loading = false;
            }
        },

        async login() {
            this.error = '';
            this.loading = true;
            this.info = 'deriving keys... (this takes ~15 seconds)';
            try {
                const keypair = await this.derivePrivateKey(this.form.username, this.form.password);
                const res = await fetch('/api/auth/login', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ username: this.form.username, password: this.form.password })
                });
                const data = await res.json();
                if (!res.ok) { this.error = data.error || 'invalid credentials'; this.info = ''; this.loading = false; return; }
                this.auth = { loggedIn: true, username: data.username, privateKey: keypair.privateKey };
                this.info = '';
                this.loading = false;
                this.navigate('inbox');
            } catch (e) {
                this.error = e.message || String(e);
                this.info = '';
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
            this.mapMarker = null;
            const mapEl = document.getElementById('map');
            if (!mapEl) {
                console.warn('Map element not found, retrying...');
                setTimeout(() => this.initMap(), 200);
                return;
            }
            // Check if the element has dimensions (not hidden by display:none)
            if (mapEl.offsetWidth === 0) {
                console.warn('Map element has zero width, retrying...');
                setTimeout(() => this.initMap(), 200);
                return;
            }
            try {
                this.map = L.map('map', { scrollWheelZoom: true }).setView([20.5937, 78.9629], 5);
                L.tileLayer('https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png', {
                    attribution: '&copy; CartoDB',
                    maxZoom: 19
                }).addTo(this.map);
                // Force recalculate after tiles load
                setTimeout(() => { if (this.map) this.map.invalidateSize(); }, 200);
                setTimeout(() => { if (this.map) this.map.invalidateSize(); }, 1000);
                this.map.on('click', (e) => {
                    if (this.mapMarker) this.map.removeLayer(this.mapMarker);
                    this.mapMarker = L.marker(e.latlng).addTo(this.map);
                    this.sendForm.lat = e.latlng.lat;
                    this.sendForm.lon = e.latlng.lng;
                    console.log('Map clicked, coordinates set:', e.latlng.lat, e.latlng.lng);
                });
                console.log('Map initialized successfully');
            } catch (e) {
                console.error('Map init failed:', e);
            }
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

        // Generate noise image AND embed payload via LSB client-side
        // This avoids Java ImageIO color space conversion that corrupts LSB data
        generateStegoImage(payloadBytes) {
            const canvas = document.createElement('canvas');
            canvas.width = 512;
            canvas.height = 512;
            const ctx = canvas.getContext('2d', { willReadFrequently: true });
            const imageData = ctx.createImageData(512, 512);
            const pixels = imageData.data;

            // Fill with random noise
            for (let i = 0; i < pixels.length; i += 4) {
                pixels[i] = Math.floor(Math.random() * 256);
                pixels[i + 1] = Math.floor(Math.random() * 256);
                pixels[i + 2] = Math.floor(Math.random() * 256);
                pixels[i + 3] = 255;
            }

            // Prepend 4-byte big-endian length header
            const header = new Uint8Array(4);
            header[0] = (payloadBytes.length >> 24) & 0xFF;
            header[1] = (payloadBytes.length >> 16) & 0xFF;
            header[2] = (payloadBytes.length >> 8) & 0xFF;
            header[3] = payloadBytes.length & 0xFF;

            const fullData = new Uint8Array(4 + payloadBytes.length);
            fullData.set(header, 0);
            fullData.set(payloadBytes, 4);

            // Embed bits into LSBs of R, G, B channels
            const totalBits = fullData.length * 8;
            for (let i = 0; i < totalBits; i++) {
                const pixelIdx = Math.floor(i / 3) * 4;
                const channel = i % 3;
                const byteIdx = Math.floor(i / 8);
                const bitPos = 7 - (i % 8);
                const bit = (fullData[byteIdx] >> bitPos) & 1;
                pixels[pixelIdx + channel] = (pixels[pixelIdx + channel] & 0xFE) | bit;
            }

            ctx.putImageData(imageData, 0, 0);
            return new Promise(resolve => canvas.toBlob(resolve, 'image/png'));
        },

        // Helper to base64 encode without stack overflow for large arrays
        arrayToBase64(uint8arr) {
            let binary = '';
            const chunk = 8192;
            for (let i = 0; i < uint8arr.length; i += chunk) {
                binary += String.fromCharCode.apply(null, uint8arr.subarray(i, i + chunk));
            }
            return btoa(binary);
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

                // Generate AES key and split into shards
                const aesKey = new Uint8Array(32);
                crypto.getRandomValues(aesKey);
                const shard1 = aesKey.slice(0, 16);
                const shard2 = aesKey.slice(16, 32);
                const shard2Base64 = this.arrayToBase64(shard2);

                // Encrypt the payload
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

                // Bundle: iv(12) + ciphertext
                const payload = new Uint8Array(12 + ciphertext.byteLength);
                payload.set(iv, 0);
                payload.set(new Uint8Array(ciphertext), 12);
                const payloadBase64 = this.arrayToBase64(payload);

                // RSA encrypt shard1
                const shard1Str = String.fromCharCode.apply(null, shard1);
                const encryptedShard1 = receiverPublicKey.encrypt(shard1Str, 'RSA-OAEP', {
                    md: forge.md.sha256.create()
                });
                const encryptedKeyBase64 = btoa(encryptedShard1);

                // Build form data
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
                    // Client-side LSB embedding into noise image
                    // This avoids server-side ImageIO color space conversion
                    const stegoBlob = await this.generateStegoImage(payload);
                    formData.append('stegoImage', stegoBlob, 'stego.png');
                }

                const res = await fetch('/api/messages/send', { method: 'POST', body: formData });
                const data = await res.json();
                if (!res.ok) { this.error = data.error || 'send failed'; this.sending = false; return; }
                this.info = 'message transmitted successfully';
                this.sendForm = { receiver: '', message: '', lat: null, lon: null, geoLocked: true, burnAfterRead: false, file: null, fileName: '' };
            } catch (e) {
                console.error('Send error:', e);
                this.error = 'error: ' + (e.message || String(e));
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

                // Fetch message metadata
                const msgRes = await fetch('/api/messages/' + this.currentMessageId);
                if (!msgRes.ok) { this.error = 'failed to load message'; this.decrypting = false; return; }
                const msg = await msgRes.json();
                console.log('Message metadata:', JSON.stringify(msg, null, 2));

                if (msg.expired) { this.error = 'message has expired'; this.decrypting = false; return; }

                // Get shard2 — either via geo-verify or directly
                if (msg.geoLocked) {
                    let lat, lon;
                    try {
                        const pos = await new Promise((resolve, reject) =>
                            navigator.geolocation.getCurrentPosition(resolve, reject, {
                                enableHighAccuracy: true,
                                timeout: 15000,
                                maximumAge: 60000
                            })
                        );
                        lat = pos.coords.latitude;
                        lon = pos.coords.longitude;
                        console.log('Got GPS coords:', lat, lon);
                    } catch (geoErr) {
                        console.log('Geolocation failed:', geoErr);
                        const coordStr = prompt('geolocation unavailable. enter your coordinates as: lat,lon');
                        if (!coordStr) {
                            this.error = 'location required for geo-locked messages.';
                            this.decrypting = false;
                            return;
                        }
                        const parts = coordStr.split(',').map(s => parseFloat(s.trim()));
                        if (parts.length !== 2 || isNaN(parts[0]) || isNaN(parts[1])) {
                            this.error = 'invalid coordinates format. use: lat,lon';
                            this.decrypting = false;
                            return;
                        }
                        lat = parts[0];
                        lon = parts[1];
                    }

                    const verifyRes = await fetch('/api/location/verify', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ lat, lon, messageId: this.currentMessageId })
                    });
                    const verifyData = await verifyRes.json();
                    console.log('Location verify result:', JSON.stringify(verifyData));

                    if (!verifyData.valid) {
                        this.error = verifyData.message || 'location verification failed. distance: ' + verifyData.distance + 'm, radius: ' + verifyData.radius + 'm';
                        this.decrypting = false;
                        return;
                    }
                    shard2 = verifyData.keyShard;
                } else {
                    shard2 = msg.keyShard;
                }

                if (!shard2) {
                    this.error = 'key shard unavailable. message may have expired or already been read (burn after read).';
                    this.decrypting = false;
                    return;
                }

                console.log('Got shard2, length:', shard2.length);

                // RSA decrypt shard1
                const encryptedShard1Raw = atob(msg.encryptedKey);
                console.log('RSA ciphertext length:', encryptedShard1Raw.length);
                const shard1Raw = this.auth.privateKey.decrypt(encryptedShard1Raw, 'RSA-OAEP', {
                    md: forge.md.sha256.create()
                });
                const shard1 = new Uint8Array(shard1Raw.length);
                for (let i = 0; i < shard1Raw.length; i++) shard1[i] = shard1Raw.charCodeAt(i);
                console.log('Decrypted shard1 length:', shard1.length);

                // Decode shard2
                const shard2Raw = atob(shard2);
                const shard2Bytes = new Uint8Array(shard2Raw.length);
                for (let i = 0; i < shard2Raw.length; i++) shard2Bytes[i] = shard2Raw.charCodeAt(i);
                console.log('Decoded shard2 length:', shard2Bytes.length);

                // Reconstruct AES key
                const aesKey = new Uint8Array(32);
                aesKey.set(shard1, 0);
                aesKey.set(shard2Bytes, 16);
                console.log('AES key reconstructed, length:', aesKey.length);

                if (msg.isDocument) {
                    // Download and decrypt document
                    const fileRes = await fetch('/api/messages/' + this.currentMessageId + '/file');
                    if (!fileRes.ok) { this.error = 'failed to download document'; this.decrypting = false; return; }
                    const encryptedBlob = await fileRes.blob();
                    const encryptedBytes = new Uint8Array(await encryptedBlob.arrayBuffer());
                    console.log('Encrypted doc size:', encryptedBytes.length);

                    const iv = encryptedBytes.slice(0, 12);
                    const ciphertext = encryptedBytes.slice(12);
                    const importedKey = await crypto.subtle.importKey('raw', aesKey, 'AES-GCM', false, ['decrypt']);
                    const plaintext = await crypto.subtle.decrypt({ name: 'AES-GCM', iv }, importedKey, ciphertext);
                    this.decryptedFileBlob = new Blob([plaintext]);
                    this.decrypted = 'document ready';
                } else {
                    // Download stego image and extract payload
                    const imgRes = await fetch('/api/messages/' + this.currentMessageId + '/image');
                    if (!imgRes.ok) { this.error = 'failed to download image'; this.decrypting = false; return; }
                    const imgBlob = await imgRes.blob();
                    console.log('Stego image size:', imgBlob.size);
                    const extractedPayload = await extractLsbPayload(imgBlob);
                    console.log('Extracted payload length:', extractedPayload.length);

                    const iv = extractedPayload.slice(0, 12);
                    const ciphertext = extractedPayload.slice(12);
                    const importedKey = await crypto.subtle.importKey('raw', aesKey, 'AES-GCM', false, ['decrypt']);
                    const plaintext = await crypto.subtle.decrypt({ name: 'AES-GCM', iv }, importedKey, ciphertext);
                    this.decrypted = new TextDecoder().decode(plaintext);
                }
                console.log('Decryption successful!');
                // Burn after read: tell server to destroy the message now
                if (msg.burnAfterRead) {
                    fetch('/api/messages/' + this.currentMessageId + '/burn', { method: 'POST' })
                        .then(() => console.log('Message burned'))
                        .catch(() => {});
                }
            } catch (e) {
                console.error('Decryption error:', e);
                this.error = 'decryption failed: ' + (e.message || e.toString());
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
            // Disable color space conversion to preserve exact pixel values
            const ctx = canvas.getContext('2d', { colorSpace: 'srgb', willReadFrequently: true });
            ctx.drawImage(img, 0, 0);
            const data = ctx.getImageData(0, 0, img.width, img.height).data;

            // Read 32-bit header for payload length
            let headerBits = '';
            for (let i = 0; i < 32; i++) {
                const pixelIdx = Math.floor(i / 3) * 4;
                const channel = i % 3;
                headerBits += (data[pixelIdx + channel] & 1).toString();
            }
            const payloadLength = parseInt(headerBits, 2);
            console.log('LSB header says payload length:', payloadLength);
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
        img.onerror = () => reject(new Error('failed to load stego image'));
        img.src = URL.createObjectURL(imageBlob);
    });
}
