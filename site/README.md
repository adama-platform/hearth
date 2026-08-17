# the example root

This is what `--root` wants. `domains/` is a flat directory of JSON files, one per virtual host,
each named for the domain it configures; `config.cfg` is the server itself. `dbs/` and `certs/` are
created on the first run and are not checked in.

```bash
just run
```

Then open <http://localhost:8080/register>, type any email address, and the code prints in the
terminal you started the server from. `owner@example.com` is in `admin_emails` here, so that
address gets straight in and can approve everybody else at `/admin`.

```
site/
  config.cfg                  the server: port 8080 for development
  domains/
    localhost.cfg             -> localhost
    example.org.cfg           -> example.org  (and *.example.org, via wildcard)
    junior.example.org.cfg    -> junior.example.org, sharing example.org's database
  dbs/                        created on first run
  certs/                      created on first run
```

```bash
just check site      # load it all, report, exit; never opens a socket
just check-verbose      # same, narrating every file
```

**Every config key, and everything else about operating this server, is in
[../MANUAL.md](../MANUAL.md).**
