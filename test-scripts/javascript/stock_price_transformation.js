// Sample transformation script
//
// input: values for each column using the column name
// return: null to ignore the row in the resultset, otherwise return a Hashmap of changed columns
//

// Create a HashMap to hold the changed column values
// If there are no changes to the column values, return an empty map
var HashMap = Java.type('java.util.HashMap'); 
var map = new HashMap(); 

// Store my 'mynewcolumn' field
map.put('mynewcolumn', 100.0);

// Add 1.0 to the adjusted close value
var new_adj_close = adj_close + 1.0;
map.put('adj_close', new_adj_close);

// Get the year from the incoming _pipelinetimestamp value
var date = new Date(_pipelinetimestamp);
var year = String(date.getFullYear());
map.put('year', year)

if(symbol == 'FAX') {
    null;  // remove this row, return null
}
else {
	map;
}
