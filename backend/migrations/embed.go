package migrations

import "embed"

// Files contains the reviewed SQL migrations distributed with the binary.
// The README keeps this directory embeddable before the domain schema exists.
//
//go:embed *
var Files embed.FS
