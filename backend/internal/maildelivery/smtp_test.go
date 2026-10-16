package maildelivery

import (
	"bufio"
	"context"
	"crypto/tls"
	"crypto/x509"
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestVerifiedTLSSMTP(t *testing.T) {
	seed := httptest.NewTLSServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
	cfg := seed.TLS.Clone()
	roots := x509.NewCertPool()
	roots.AddCert(seed.Certificate())
	seed.Close()
	base, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	listener := tls.NewListener(base, cfg)
	defer listener.Close()
	received := make(chan string, 1)
	go func() {
		conn, e := listener.Accept()
		if e != nil {
			return
		}
		defer conn.Close()
		_ = conn.SetDeadline(time.Now().Add(5 * time.Second))
		r := bufio.NewReader(conn)
		fmt.Fprint(conn, "220 test ESMTP\r\n")
		var body strings.Builder
		for {
			line, e := r.ReadString('\n')
			if e != nil {
				return
			}
			switch {
			case strings.HasPrefix(line, "EHLO"):
				fmt.Fprint(conn, "250 test\r\n")
			case strings.HasPrefix(line, "MAIL"), strings.HasPrefix(line, "RCPT"):
				fmt.Fprint(conn, "250 OK\r\n")
			case strings.HasPrefix(line, "DATA"):
				fmt.Fprint(conn, "354 data\r\n")
				for {
					v, e := r.ReadString('\n')
					if e != nil {
						return
					}
					if v == ".\r\n" {
						break
					}
					body.WriteString(v)
				}
				fmt.Fprint(conn, "250 queued\r\n")
			case strings.HasPrefix(line, "QUIT"):
				fmt.Fprint(conn, "221 bye\r\n")
				received <- body.String()
				return
			default:
				return
			}
		}
	}()
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	sender := Sender{Config: Config{Address: listener.Addr().String(), From: "sender@example.test", Mode: "tls"}, Roots: roots}
	if err = sender.Send(ctx, "owner@example.test", "private-code-body\r\n"); err != nil {
		t.Fatal(err)
	}
	select {
	case body := <-received:
		if !strings.Contains(body, "private-code-body") || !strings.Contains(body, "To: owner@example.test") {
			t.Fatal("SMTP lost message")
		}
	case <-ctx.Done():
		t.Fatal("SMTP did not finish")
	}
}
func TestRejectPlaintextAndHeaderInjection(t *testing.T) {
	for _, cfg := range []Config{{Address: "localhost:25", From: "x@example.test", Mode: "plain"}, {Address: "localhost:25", From: "x@example.test\r\nBcc: bad@example.test", Mode: "tls"}, {Address: "localhost:25", From: "x@example.test", Mode: "tls", Username: "user"}} {
		if cfg.Validate() == nil {
			t.Fatal("unsafe SMTP configuration accepted")
		}
	}
}
func TestSMTPRejectsSTARTTLSDowngrade(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	commands := make(chan string, 1)
	go func() {
		conn, err := listener.Accept()
		if err != nil {
			return
		}
		defer conn.Close()
		_ = conn.SetDeadline(time.Now().Add(5 * time.Second))
		r := bufio.NewReader(conn)
		fmt.Fprint(conn, "220 test ESMTP\r\n")
		var seen strings.Builder
		for {
			line, err := r.ReadString('\n')
			if err != nil {
				commands <- seen.String()
				return
			}
			seen.WriteString(line)
			fmt.Fprint(conn, "250 test-without-starttls\r\n")
		}
	}()
	sender := Sender{Config: Config{Address: listener.Addr().String(), From: "sender@example.test", Mode: "starttls"}}
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	if err = sender.Send(ctx, "recipient@example.test", "secret-not-to-send"); err == nil {
		t.Fatal("SMTP plaintext fallback accepted")
	}
	select {
	case sent := <-commands:
		if strings.Contains(sent, "MAIL") || strings.Contains(sent, "DATA") || strings.Contains(sent, "secret") {
			t.Fatal("message sent before TLS")
		}
	case <-ctx.Done():
		t.Fatal("SMTP downgrade did not terminate")
	}
}
