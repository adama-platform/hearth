# Hearth — Mission

## What it is

**One person's infrastructure, run on one machine, with AI as the interface.**

A gym log, a ranch to-do list, habits that graduate, and a way to get five friends to agree on a
Thursday — all in one jar, all reachable by an agent over MCP, none of it hosted by anybody.

This is a **Ranch OS**. It is built for the person who wrote it. It is open source because somebody
else with a ranch and a barbell and too many browser tabs can take it and bend it to their own
shape, which is now a thing a person can do in an afternoon with an AI rather than with a fork and a
year.

## What changed

This was, for a while, a community server: accounts, a discussion board, a calendar, an invitation
funnel, the works. That version is in the git history and none of it is coming back.

The honest reason is that it was software looking for a community rather than a person with a
problem. What is left, and what is being built now, is the opposite: **a list of things one person
actually does every day**, made addressable by an agent.

**Multi-user stays**, and stays deliberately. Not to host a community — to invite four friends into
the parts where more than one person is needed: a vote about when to play board games, a habit
somebody else can see. Everything else is one person's.

## Four commitments

**One person's scale.** Me, and a handful of people I know. Not a thousand. One database file, caches
in memory, no queue, no second process. Every design choice takes the simple road and says why in a
comment.

**Cheap and boring to run.** One jar with an embedded database. Backup is copying a directory,
upgrade is replacing a file. No version numbers, because nobody resolves this from a repository: the
jar says `MAIN` and the commit is the identity.

**AI is the interface, not a feature.** The screens exist and work, but the design target is an agent
with an MCP connection doing the typing. A tool description here is a prompt, the guidance is
generated from what exists rather than written down, and anything a person can do an agent should be
able to do *as* that person.

**Small enough to check.** The oldest commitment and the reason for the rest. Every feature is one
whose failure modes fit in one head — which matters more, not less, now that a model can rewrite the
place. **The right answer to "this would be useful" is sometimes "and it would be one more thing
nobody is checking."**

## What it does

- **The gym.** A per-person Hevy key, and MCP tools over Hevy's API: read workouts, build routines,
  and **invent exercises** — because the mobility work that matters is not in anybody's standard
  list, and a routine you cannot express is a routine you do not do.
- **The ranch.** Tasks that are done or not done, or that walk a state machine somebody defined.
  Habits with a daily or weekly cadence, tracked, and able to **graduate** when they have done their
  job. A daily sheet of what has to happen, and a horizon to pull from.
- **Getting people together.** Votes an agent can open and other agents can vote in, narrowing a
  pool of options down to something the humans decide between. Availability without handing anybody
  your calendar: a weekly shape and an ICS link, and the agent is told which of the two it is
  getting.
- **Underneath:** accounts and approval, a website, files, mail in and out, push, TLS, and dynamic
  pages whose body is a program.

## Never

Never a feed ranked by engagement. Never money moving through it. Never tracking anybody. Never
requiring a company to exist. Never larger than one person can check.

## For whoever else finds this

Take it. It is MIT. It is shaped for one person's ranch and gym, and the useful thing about that is
not the shape — it is that the whole of it fits in a context window, so you can ask an AI to make it
yours rather than asking it to understand somebody else's platform.
