# Distraction science behind grod

What the research says about short-form feeds and about tools that try to curb them, and how that shapes grod's design. Each paper was opened at its source (paper or abstract page) on 2026-11-06. Most of the harm evidence is correlational or from small samples: treat it as motivation, not proof.

## Harms of short-form video and infinite scroll

- **Chiossi, Haliburton, Ou, Butz, Schmidt (2023).** "Short-Form Videos Degrade Our Capacity to Retain Intentions: Effect of Context Switching On Prospective Memory." CHI '23. https://doi.org/10.1145/3544548.3580778. In a study with 60 people split into groups, only the TikTok group got worse at remembering a planned action; Twitter and YouTube did not. The authors attribute this to short clips combined with rapid context switching.
- **Nguyen et al. (2025).** "Feeds, Feelings, and Focus: Cognitive and Mental Health Correlates of Short-Form Video Use." *Psychological Bulletin* 151(9). https://doi.org/10.1037/bul0000498. A meta-analysis of 71 studies (N≈98k). Heavier use goes with poorer attention and inhibitory control (r≈−.34) and with more anxiety and stress. Addiction-like use is a stronger warning sign than raw time spent.
- **Yan et al. (2024).** *Frontiers in Human Neuroscience*. https://doi.org/10.3389/fnhum.2024.1383913. EEG with the attention network test: people more prone to short-video addiction showed weaker self-control and weaker executive attention.
- **Chen et al. (2023).** *Behaviour & Information Technology* 42(16). Eye-tracking plus a Stroop task with addicted and non-addicted Douyin users: the addicted group had more trouble keeping attention on task.
- **Sharma, Lee, Johnson (2022).** "The Dark at the End of the Tunnel: Doomscrolling on Social Media Newsfeeds." *Technology, Mind, and Behavior* 3(1). https://doi.org/10.1037/tmb0000059. A validated scale for doomscrolling, which is linked to anxiety, passive use and poor self-control.
- **Ruiz, Molina León, Heuer (2024).** "Design Frictions on Social Media." https://arxiv.org/abs/2407.18803. People who had to react to each post before seeing more remembered more than people on infinite scroll, but most found the friction annoying.

## Digital self-control interventions

- **Lyngs et al. (2019).** "Self-Control in Cyberspace." CHI '19. https://doi.org/10.1145/3290605.3300361. A review of 367 tools: most block or track; few remove the trigger itself.
- **Lyngs et al. (2020).** "I Just Want to Hack Myself to Not Get Distracted." CHI '20. https://arxiv.org/abs/2001.04180. Over 6 weeks (N=58), removing the Facebook newsfeed reduced distraction but caused fear of missing out. The authors recommend letting people block what *they* define as distracting.
- **Purohit et al. (2023).** "Starving the Newsfeed for Social Media Detox." CHI '23. https://doi.org/10.1145/3544548.3581187. Field experiment (N=138): removing the feed cut Facebook time by 64%; a reduced feed cut it by 39%.
- **Zhang, Lukoff, Rao, Baughan, Hiniker (2022).** "Monitoring Screen Time or Redesigning It?" CHI '22. https://doi.org/10.1145/3491102.3517722. In a Twitter client study, redesigning the interface raised users' sense of agency; usage dashboards did not.
- **Lukoff et al. (2021).** "How the Design of YouTube Influences User Sense of Agency." CHI '21. https://arxiv.org/abs/2101.11778. Recommendations and autoplay lower users' sense of control; search and playlists raise it.
- **Grüning, Riedel, Lorenz-Spreen (2023).** "Directing smartphone use through the self-nudge app one sec." *PNAS* 120(8). https://doi.org/10.1073/pnas.2213114120. Over 6 weeks (N=280), users backed out about 36% of the time and opening attempts fell 57%. The back-out option and the delay did the work; the reflective message alone did not. The lead author is affiliated with one sec.
- **Kovacs, Wu, Bernstein (2018).** "Rotating Online Behavior Change Interventions Increases Effectiveness But Also Increases Attrition." CSCW '18. https://doi.org/10.1145/3274364. Rotating interventions cut time but nearly doubled uninstalls; a short explanation at the moment of change roughly halved that.
- **Kovacs, Wu, Bernstein (2021).** "Not Now, Ask Later." CHI '21. https://arxiv.org/abs/2101.11743. Users gradually switch to easier interventions while planning to make them stricter "later."
- **Kim, Jung, Ko, Lee (2019).** "GoalKeeper." IMWUT 3(1). Hard lockouts work better than warnings but frustrate people more.
- **Okeke, Sobolev, Dell, Estrin (2018).** "Good Vibrations." MobileHCI '18. https://doi.org/10.1145/3229434.3229463. A vibration nudge cut Facebook time by more than 20%, but use returned to baseline once the nudge was removed.
- **Monge Roffarello & De Russis (2023).** "Achieving Digital Wellbeing Through Digital Self-control Tools." ACM TOCHI 30(4). https://doi.org/10.1145/3571810. These tools work in the short term, but the effect fades.
- **Monge Roffarello & De Russis (2023).** "Nudging Users or Redesigning Interfaces?" GoodIT '23. https://doi.org/10.1145/3582515.3609523. Both a nudge and a redesign reduced passive scrolling.
- **Fitz, Kushlev, Jagannathan, Lewis, Paliwal, Ariely (2019).** "Batching smartphone notifications can improve well-being." *Computers in Human Behavior* 101. https://doi.org/10.1016/j.chb.2019.07.016. Field experiment, N=237. Getting notifications three times a day improved attention, productivity and mood. No notifications at all raised anxiety and fear of missing out. Hourly batching helped little.
- **Meinhardt et al. (2025).** "Scrolling in the Deep." https://arxiv.org/abs/2501.11814. Context matters: being at home in a bad mood made interventions work more slowly.
- **Meinhardt, Dragic, Colley, Lukoff, Rukzio (2026).** "Can't Stop: Gradual Interventions for Infinite Scrolling on Short-Form Video Platforms." IMWUT. https://arxiv.org/abs/2607.15818. 7 days, N=104. People learn to ignore simple pop-ups; gradually increasing visual friction stays effective longest.

## Design principles grod follows

1. **Remove the feature, not the app.** Only the Reels, Shorts and similar feeds go; stories, posts, DMs and search stay (Lyngs 2020; Purohit 2023; Zhang 2022). Whole-app blocks are kept for apps that are nothing but feed (TikTok).
2. **Remove the trigger before the click.** The Reels and Shorts tabs and shelves are hidden or covered, so nothing pulls the user in (Lyngs 2019).
3. **One item is fine, the scroll is not.** A Short opens as a normal video; a reel sent in a DM plays once. Swiping on to the next one is what gets stopped (Chiossi 2023: the harm comes from rapid switching).
4. **Stable, explained interventions.** Rules do not rotate. When a rule acts, grod says which one and what stays usable (Kovacs 2018).
5. **Light, private logging.** Each platform only counts how often each rule fired, on the device. There is no dashboard, because dashboards do not raise agency (Zhang 2022; Okeke 2018).
6. **Notifications in batches, not off.** The phone holds app notifications and delivers them together. Calls and alarms still ring, so nothing urgent is lost (Fitz 2019).
7. **The user defines distraction.** Rules are data the user (or their agent) changes in one place (Lyngs 2020).

## Not built, with the evidence for each

- Making a rule harder to turn off than to turn on, because users drift toward weaker settings (Kovacs 2021).
- Time-of-day schedules, because context changes how well interventions work (Meinhardt 2025).
- A delayed escape hatch or gradually increasing friction instead of a hard stop (Grüning 2023; Meinhardt 2026). The user asked for the feed to be gone, not slowed down.
