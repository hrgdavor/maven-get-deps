import { readFileSync, writeFileSync } from 'fs';

function alignTable(rows) {
  const tableData = rows.map(row => 
    row.split('|').map(cell => cell.trim())
  );

  const numCols = Math.max(...tableData.map(r => r.length));
  const colWidths = Array(numCols).fill(0);

  // Calculate max widths for each column
  for (let row of tableData) {
    row.forEach((cell, i) => {
      if (cell.length > colWidths[i]) colWidths[i] = cell.length;
    });
  }

  // Format rows with perfect padding
  return tableData.map((row, rowIndex) => {
    const formattedCells = row.map((cell, i) => {
      // Handle the separator row (the |---| line)
      if (rowIndex === 1 && /^-+$/.test(cell)) {
        return '-'.repeat(colWidths[i]);
      }
      return cell.padEnd(colWidths[i]);
    });
    
    // Clean up empty edge cases and join
    return `| ${formattedCells.join(' | ')} |`.replace(/\s+\|$/, ' |');
  }).join('\n');
}

function processMarkdown(content) {
  const lines = content.split('\n');
  let result = [];
  let currentTable = [];

  for (let line of lines) {
    if (line.trim().startsWith('|')) {
      currentTable.push(line);
    } else {
      if (currentTable.length > 0) {
        result.push(alignTable(currentTable));
        currentTable = [];
      }
      result.push(line);
    }
  }
  if (currentTable.length > 0) result.push(alignTable(currentTable));
  return result.join('\n');
}

// Get file path from command line arguments
const filePath = process.argv[2];
if (!filePath) {
  console.error("Please provide a file path.");
  process.exit(1);
}

try {
  const content = readFileSync(filePath, 'utf-8');
  writeFileSync(filePath, processMarkdown(content));
  console.log(`✅ Aligned tables in: ${filePath}`);
} catch (err) {
  console.error(`Error: ${err.message}`);
}
