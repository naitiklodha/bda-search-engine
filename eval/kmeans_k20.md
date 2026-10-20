# K-means, k = 20

Simple English Wikipedia, 209,083 articles clustered (those with at least 10 vocabulary terms; 1,023 shorter ones excluded); vocabulary 20,000 terms (5 <= df <= 0.1·N, top by df); weights (1 + ln tf)·idf, L2-normalised; maxIter 20, seed 42.
Fit time 43 s; within-set sum of squared errors 200834.6; silhouette (20k sample) 0.0132.

| Cluster | Size | Top 10 terms (centroid weight) | 5 random titles |
|---|---|---|---|
| 0 | 1,632 | chemic, compound, acid, oxid, atom, react, formula, carbon, ion, hydrogen | Reaction Agostini; Nicotinamide adenine dinucleotide phosphate; Oxalic acid; Arsenic trifluoride; Mineral (nutrient) |
| 1 | 10,527 | seri, televis, charact, episod, show, anim, channel, tv, comic, air | Marcella (TV series); I Still Know What You Did Last Summer; Patrizio Prata; Jessica Cruz; Silk (character) |
| 2 | 12,810 | actor, actress, role, televis, her, best, award, seri, film, director | Sean Lock; 1926; Martin West (actor); Flex-Deon Blake; Danielle |
| 3 | 31,735 | mean, speci, usual, differ, type, exampl, often, word, languag, veri | Kaaba; Jean-Baptiste de Lamarck; Scaptia beyonceae; Disaster; China Airlines Flight 611 |
| 4 | 1,109 | basketbal, nba, player, team, coach, associ, colleg, profession, game, wheelchair | Utah Jazz; Moses Malone; Naismith Memorial Basketball Hall of Fame; Pat Summerall; Caldwell Jones |
| 5 | 13,077 | footbal, team, leagu, cup, club, player, season, statist, career, goal | Harry Holman (footballer); Goro Yamada; Hiroyuki Abe; Aldershot Town F.C.; Double |
| 6 | 11,718 | album, song, singl, number, studio, releas, band, chart, track, singer | Blood Red and Goin' Down; Amor Prohibido (song); My Love Is for Real; Legs (song); Steve Harris |
| 7 | 6,974 | direct, star, drama, distribut, comedi, pictur, base, novel, actor, romant | Jung Gun-joo; Those Boys!; We Didn't Learn This; Tom Green; Cobra (1986 movie) |
| 8 | 2 | andrew, laura, runtim, finnei, greta, hawlei, pension, 1994, respect, hate | Tally Hall; The Browning Version |
| 9 | 2,038 | frog, scientist, danger, tree, forest, abov, egg, tadpol, meter, seen | Polypedates leucomystax; Colostethus ucumari; Bromeliad tree frog; Oruge tree frog; Eupsophus calcaratus |
| 10 | 1,893 | depart, commun, franc, saint, arrondiss, de, region, le, sur, loir | Savigny-le-Temple; Ouville-la-Bien-Tournée; Arrondissement of Castres; Maine (river); Arrondissement of Beaune |
| 11 | 2,803 | municip, district, canton, switzerland, merg, provinc, town, former, villag, germani | Mosteiros, Cape Verde (municipality); Zwartewaterland; Remlingen, Lower Saxony; Orbe (district); Le Locle District |
| 12 | 49,270 | univers, 2020, march, august, april, decemb, septemb, februari, juli, octob | Ramachandra Babu; Dan Magill; Joaquín Turina; Swimming at the 1900 Summer Olympics – Men's 1000 metre freestyle; List of museums in Seoul |
| 13 | 3,379 | game, video, nintendo, playstat, releas, player, olymp, develop, gameplai, seri | Donkey Kong Land; Fox Interactive; King of the Monsters (video game); Lady Dimitrescu; Tony Hawk's Pro Skater |
| 14 | 3,978 | counti, censu, 2020, popul, seat, town, area, locat, commun, unincorpor | Sarchehan County; Brookhaven, New York; Discovery Bay, California; Edgecombe County, North Carolina; Richmond County |
| 15 | 3,921 | cell, caus, diseas, bodi, blood, medic, symptom, infect, human, drug | Lung cancer; Naegleria fowleri; Allopurinol; XXYY syndrome; Major histocompatibility complex |
| 16 | 16,290 | war, king, armi, ii, forc, emperor, battl, empir, militari, him | Cathay Pacific; 1511; Apple Daily; Tikendrajit Singh; Eiparnome |
| 17 | 16,138 | politician, parti, elect, minist, presid, democrat, repres, senat, serv, prime | Luigi Arisio; Georg Kronawitter; Guido Bellido; Hwang Sun-hui; Adnan Pachachi |
| 18 | 369 | volleybal, fivb, women, championship, player, team, vollei, medal, prix, european | Modena Volleyball Club; Melis Durul; Lays Freitas; Lyubka Debarlieva; Michelle Nogueras |
| 19 | 19,420 | town, river, area, district, north, south, provinc, region, popul, island | Wharram Percy; Eurasian plate; Farsley; Saint Vincent and the Grenadines; Jing'an District |
