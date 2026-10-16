// Package maildelivery sends authentication messages only over verified TLS.
package maildelivery

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"net"
	"net/mail"
	"net/smtp"
	"strings"
	"time"
)

type Config struct{ Address, Username, Password, From, Mode string }

func (c Config) Validate() error {
	host, port, err := net.SplitHostPort(c.Address)
	if err != nil || host == "" || port == "" || strings.ContainsAny(c.Address, "\r\n") {
		return errors.New("invalid SMTP address")
	}
	from, err := mail.ParseAddress(c.From)
	if err != nil || from.Address != c.From || strings.ContainsAny(c.From, "\r\n") {
		return errors.New("invalid SMTP sender")
	}
	if c.Mode != "starttls" && c.Mode != "tls" {
		return errors.New("SMTP requires verified TLS")
	}
	if (c.Username == "") != (c.Password == "") {
		return errors.New("incomplete SMTP credentials")
	}
	return nil
}

type Sender struct {
	Config Config
	Roots  *x509.CertPool
}

func (s Sender) Send(ctx context.Context, to, body string) error {
	if err := s.Config.Validate(); err != nil {
		return errors.New("email delivery unavailable")
	}
	recipient, err := mail.ParseAddress(to)
	if err != nil || recipient.Address != to || strings.ContainsAny(to, "\r\n") {
		return errors.New("email delivery unavailable")
	}
	host, _, _ := net.SplitHostPort(s.Config.Address)
	dialer := net.Dialer{Timeout: 8 * time.Second}
	conn, err := dialer.DialContext(ctx, "tcp", s.Config.Address)
	if err != nil {
		return errors.New("email delivery unavailable")
	}
	defer conn.Close()
	deadline := time.Now().Add(10 * time.Second)
	if d, ok := ctx.Deadline(); ok && d.Before(deadline) {
		deadline = d
	}
	_ = conn.SetDeadline(deadline)
	tlsConfig := &tls.Config{ServerName: host, MinVersion: tls.VersionTLS12, RootCAs: s.Roots}
	if s.Config.Mode == "tls" {
		secured := tls.Client(conn, tlsConfig)
		if err = secured.HandshakeContext(ctx); err != nil {
			return errors.New("email delivery unavailable")
		}
		conn = secured
	}
	client, err := smtp.NewClient(conn, host)
	if err != nil {
		return errors.New("email delivery unavailable")
	}
	defer client.Close()
	if s.Config.Mode == "starttls" {
		if ok, _ := client.Extension("STARTTLS"); !ok {
			return errors.New("email delivery unavailable")
		}
		if err = client.StartTLS(tlsConfig); err != nil {
			return errors.New("email delivery unavailable")
		}
	}
	if s.Config.Username != "" {
		if err = client.Auth(smtp.PlainAuth("", s.Config.Username, s.Config.Password, host)); err != nil {
			return errors.New("email delivery unavailable")
		}
	}
	if err = client.Mail(s.Config.From); err != nil {
		return errors.New("email delivery unavailable")
	}
	if err = client.Rcpt(to); err != nil {
		return errors.New("email delivery unavailable")
	}
	writer, err := client.Data()
	if err != nil {
		return errors.New("email delivery unavailable")
	}
	message := "From: " + s.Config.From + "\r\nTo: " + to + "\r\nSubject: Accounting confirmation\r\nMIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: 8bit\r\n\r\n" + body
	if _, err = writer.Write([]byte(message)); err != nil {
		return errors.New("email delivery unavailable")
	}
	if err = writer.Close(); err != nil {
		return errors.New("email delivery unavailable")
	}
	if err = client.Quit(); err != nil {
		return errors.New("email delivery unavailable")
	}
	return nil
}
