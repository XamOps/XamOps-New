import fs from 'fs';
import { XMLParser } from 'fast-xml-parser';
import glob from 'glob';

const parser = new XMLParser({ ignoreAttributes: false, isArray: (name) => name === 'dependency' });

glob('**/pom.xml', (err, files) => {
    if (err) throw err;
    files.forEach(file => {
        if (file.includes('node_modules')) return;
        const xml = fs.readFileSync(file, 'utf8');
        const obj = parser.parse(xml);

        let deps = [];
        if (obj.project && obj.project.dependencies && obj.project.dependencies.dependency) {
            deps = obj.project.dependencies.dependency;
        }

        const seen = new Set();
        deps.forEach(d => {
            const key = `${d.groupId}:${d.artifactId}`;
            if (seen.has(key)) {
                console.log(`Duplicate ${key} in ${file}`);
            }
            seen.add(key);
        });
    });
});
