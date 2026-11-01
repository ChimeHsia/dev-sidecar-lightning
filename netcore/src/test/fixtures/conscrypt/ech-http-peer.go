// Independent loopback peer: Go standard library only; never part of the APK.
package main

import (
	"bufio"
	"bytes"
	"crypto/ecdh"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"math/big"
	"net"
	"os"
	"time"
)

func u16(n int) []byte {
	if n < 0 || n > 65535 {
		panic("uint16 overflow")
	}
	return []byte{byte(n >> 8), byte(n)}
}

func vector16(b []byte) []byte { return append(u16(len(b)), b...) }

func config(id byte) (tls.EncryptedClientHelloKey, []byte) {
	key, err := ecdh.X25519().GenerateKey(rand.Reader)
	must(err)
	content := []byte{id, 0, 0x20}
	content = append(content, vector16(key.PublicKey().Bytes())...)
	content = append(content, vector16([]byte{0, 1, 0, 1})...)
	name := []byte("cover.example")
	content = append(content, 64, byte(len(name)))
	content = append(content, name...)
	content = append(content, 0, 0)
	wire := append([]byte{0xfe, 0x0d}, vector16(content)...)
	return tls.EncryptedClientHelloKey{Config: wire, PrivateKey: key.Bytes(), SendAsRetry: true}, vector16(wire)
}

func certificates(mode string) (tls.Certificate, []byte) {
	caKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	must(err)
	now := time.Now()
	ca := &x509.Certificate{
		SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "Ephemeral Go ECH test root"},
		NotBefore: now.Add(-time.Hour), NotAfter: now.Add(24 * time.Hour),
		IsCA: true, BasicConstraintsValid: true,
		KeyUsage: x509.KeyUsageCertSign | x509.KeyUsageCRLSign,
	}
	caDER, err := x509.CreateCertificate(rand.Reader, ca, ca, &caKey.PublicKey, caKey)
	must(err)
	leafKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	must(err)
	leaf := &x509.Certificate{
		SerialNumber: big.NewInt(2), Subject: pkix.Name{CommonName: "Deliberately unused CN"},
		DNSNames:  []string{"inner.example", "verify.example", "cover.example"},
		NotBefore: now.Add(-time.Hour), NotAfter: now.Add(time.Hour),
		KeyUsage: x509.KeyUsageDigitalSignature, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
	}
	switch mode {
	case "wrong-host":
		leaf.DNSNames = []string{"wrong.example"}
	case "expired":
		leaf.NotBefore, leaf.NotAfter = now.Add(-48*time.Hour), now.Add(-24*time.Hour)
	}
	leafDER, err := x509.CreateCertificate(rand.Reader, leaf, ca, &leafKey.PublicKey, caKey)
	must(err)
	return tls.Certificate{Certificate: [][]byte{leafDER, caDER}, PrivateKey: leafKey}, caDER
}

// Mutate only the encrypted ECH payload's last byte, preserving all wire lengths.
func corruptECH(record []byte) bool {
	if len(record) < 44 || record[0] != 22 || record[5] != 1 {
		return false
	}
	p := 5 + 4 + 2 + 32
	if p >= len(record) {
		return false
	}
	p += 1 + int(record[p])
	if p+2 > len(record) {
		return false
	}
	n := int(binary.BigEndian.Uint16(record[p:]))
	p += 2 + n
	if p >= len(record) {
		return false
	}
	p += 1 + int(record[p])
	if p+2 > len(record) {
		return false
	}
	n = int(binary.BigEndian.Uint16(record[p:]))
	p += 2
	end := p + n
	if end != len(record) {
		return false
	}
	for p+4 <= end {
		typ := binary.BigEndian.Uint16(record[p:])
		n = int(binary.BigEndian.Uint16(record[p+2:]))
		p += 4
		if p+n > end {
			return false
		}
		if typ == 0xfe0d && n > 16 && record[p] == 0 {
			record[p+n-1] ^= 1
			return true
		}
		p += n
	}
	return false
}

type tamperConn struct {
	net.Conn
	mode     string
	loaded   bool
	pending  *bytes.Reader
	tampered bool
}

func (c *tamperConn) Read(b []byte) (int, error) {
	if c.mode == "tamper" && !c.loaded {
		c.loaded = true
		head := make([]byte, 5)
		if _, err := io.ReadFull(c.Conn, head); err != nil {
			return 0, err
		}
		n := int(binary.BigEndian.Uint16(head[3:]))
		body := make([]byte, n)
		if _, err := io.ReadFull(c.Conn, body); err != nil {
			return 0, err
		}
		record := append(head, body...)
		c.tampered = corruptECH(record)
		c.pending = bytes.NewReader(record)
	}
	if c.pending != nil && c.pending.Len() > 0 {
		return c.pending.Read(b)
	}
	return c.Conn.Read(b)
}

func (c *tamperConn) Write(b []byte) (int, error) {
	if c.mode == "record-tamper" && !c.tampered {
		b = bytes.Clone(b)
		for p := 0; p+5 <= len(b); {
			n := int(binary.BigEndian.Uint16(b[p+3:]))
			if p+5+n > len(b) {
				break
			}
			if b[p] == 23 && n > 16 {
				b[p+5+n-1] ^= 1
				c.tampered = true
				break
			}
			p += 5 + n
		}
	}
	return c.Conn.Write(b)
}

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func emit(value any) { must(json.NewEncoder(os.Stdout).Encode(value)) }

func main() {
	mode := flag.String("mode", "accepted", "accepted/reject/noech/wrong-host/expired/tamper/record-tamper/stall")
	wait := flag.Int("accept-timeout-seconds", 120, "host harness setup window")
	flag.Parse()
	advertised, list := config(7)
	key := advertised
	if *mode == "reject" {
		key, _ = config(8)
	}
	cert, caDER := certificates(*mode)
	cfg := &tls.Config{
		MinVersion: tls.VersionTLS13, MaxVersion: tls.VersionTLS13,
		Certificates: []tls.Certificate{cert}, NextProtos: []string{"http/1.1"},
		EncryptedClientHelloKeys: []tls.EncryptedClientHelloKey{key},
		SessionTicketsDisabled:   true,
	}
	if *mode == "noech" {
		cfg.EncryptedClientHelloKeys = nil
	}
	seenName := ""
	cfg.GetConfigForClient = func(info *tls.ClientHelloInfo) (*tls.Config, error) {
		seenName = info.ServerName
		return nil, nil
	}
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	must(err)
	defer listener.Close()
	must(listener.(*net.TCPListener).SetDeadline(time.Now().Add(time.Duration(*wait) * time.Second)))
	emit(map[string]any{
		"event": "ready", "port": listener.Addr().(*net.TCPAddr).Port,
		"config_b64": base64.StdEncoding.EncodeToString(list), "ca_b64": base64.StdEncoding.EncodeToString(caDER),
	})
	raw, err := listener.Accept()
	must(err)
	defer raw.Close()
	must(raw.SetDeadline(time.Now().Add(8 * time.Second)))
	report := map[string]any{"event": "result", "ech_accepted": false, "application_bytes": 0, "server_name": ""}
	if *mode == "stall" {
		_, err = io.Copy(io.Discard, raw)
		report["error"] = fmt.Sprint(err)
		emit(report)
		return
	}
	wrapped := &tamperConn{Conn: raw, mode: *mode}
	conn := tls.Server(wrapped, cfg)
	defer conn.Close()
	err = conn.Handshake()
	report["ech_accepted"] = conn.ConnectionState().ECHAccepted
	report["server_name"] = seenName
	report["tampered"] = wrapped.tampered
	if err == nil {
		reader := bufio.NewReader(conn)
		line := ""
		total := 0
		for total < 16384 {
			var part string
			part, err = reader.ReadString('\n')
			total += len(part)
			if line == "" {
				line = part
			}
			if err != nil || part == "\r\n" {
				break
			}
		}
		report["application_bytes"] = total
		if err == nil && total < 16384 {
			report["request_line"] = line
			body := "GO-ECH " + line
			_, err = fmt.Fprintf(conn, "HTTP/1.1 200 OK\r\nContent-Length: %d\r\nConnection: close\r\n\r\n%s", len(body), body)
		}
	}
	report["error"] = fmt.Sprint(err)
	emit(report)
}
