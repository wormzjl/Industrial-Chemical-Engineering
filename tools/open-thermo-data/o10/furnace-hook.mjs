import fs from 'fs';
function edit(f, pairs) {
  let s = fs.readFileSync(f, 'utf8'); const nl = s.includes('\r\n') ? '\r\n' : '\n'; s = s.replace(/\r\n/g, '\n');
  for (const [a, b] of pairs) { if (!s.includes(a)) throw new Error(f + ' missing: ' + a.slice(0, 80)); if (s.indexOf(a) !== s.lastIndexOf(a)) throw new Error('dup ' + a.slice(0,60)); s = s.replace(a, b); }
  fs.writeFileSync(f, s.replace(/\n/g, nl));
}
edit('src/main/java/com/wormzjl/icheme/science/flowsheet/unit/ColumnUnit.java', [
[`            if (!(outcome instanceof V3ColumnOutcome.Success)) outcome = V3ColumnCalculator.calculate(input, checkpoint::run, 0, 0, START, V3NeuralRegistry.bundled().bind(catalog, input), screen);
            return outcome;`, `            if (!(outcome instanceof V3ColumnOutcome.Success)) outcome = V3ColumnCalculator.calculate(input, checkpoint::run, 0, 0, START, V3NeuralRegistry.bundled().bind(catalog, input), screen);
            double feed = 0; for (double f : input.feedComponentMolarFlowsMolPerSecond()) feed += f;
            StringBuilder d = new StringBuilder("FURN solve feedT=" + String.format("%.3f", input.feedTemperatureKelvin()) + " feed=" + String.format("%.3f", feed) + " -> ");
            if (outcome instanceof V3ColumnOutcome.Success s) {
                var r = s.result(); var ins = r.inspection().orElseThrow(); var led = r.dutyLedger().orElseThrow();
                double minL = Double.MAX_VALUE, minV = Double.MAX_VALUE; int minLn = -1, minVn = -1;
                for (int n = 0; n < ins.nodes().size(); n++) { var nd = ins.nodes().get(n);
                    if (n > 0 && n < ins.nodes().size() - 1 && nd.liquidMolPerSecond() < minL) { minL = nd.liquidMolPerSecond(); minLn = n; }
                    if (n > 0 && nd.vaporMolPerSecond() < minV) { minV = nd.vaporMolPerSecond(); minVn = n; } }
                double out = 0, outH = 0; for (var st : r.streams()) { out += st.massFlowKgPerSecond(); }
                d.append("SUCCESS path=").append(s.result().getClass().getSimpleName()).append(String.format(" Qc=%.4f MW Qr=%.4f MW feedH=%.4f MW steamH=%.4f MW", led.condenserWatts() / 1e6, led.reboilerWatts() / 1e6, led.feedEnthalpyWatts() / 1e6, led.steamEnthalpyWatts() / 1e6));
                d.append(String.format(" minL(tray %d)=%.3f minV(node %d)=%.3f mol/s", minLn, minL, minVn, minV));
                d.append(String.format(" T1=%.2f Tfeed-tray=%.2f Tbottom=%.2f", ins.nodes().get(1).temperatureKelvin(), ins.nodes().get(input.feedStageNumber()).temperatureKelvin(), ins.nodes().get(ins.nodes().size() - 1).temperatureKelvin()));
                for (var st : r.streams()) d.append(String.format(" %s=%.4f", st.streamId(), st.massFlowKgPerSecond()));
                int failed = 0; for (var c : r.acceptanceAudit().checks()) if (!c.passed()) { failed++; d.append(" FAILEDCHECK ").append(c.family()); }
                d.append(" advisory=").append(r.acceptanceAudit().advisoryEvidence()); d.append(" checks=").append(r.acceptanceAudit().checks().size()).append(" failed=").append(failed);
                for (var c : r.acceptanceAudit().checks()) if (c.family().contains("MASS") || c.family().contains("ENERGY") || c.family().contains("DRY") || c.family().contains("CLOSURE")) d.append(String.format(" [%s %.3g/%.3g]", c.family(), c.value(), c.limit()));
            } else d.append(outcome);
            System.out.println(d.toString().replace('\\n', ' '));
            return outcome;`],
]);
